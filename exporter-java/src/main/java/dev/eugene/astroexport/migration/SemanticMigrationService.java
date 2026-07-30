package dev.eugene.astroexport.migration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.fs.AtomicExchange;
import dev.eugene.astroexport.fs.JnaAtomicExchange;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestResult;
import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import dev.eugene.astroexport.references.VaultReferenceCatalog;
import dev.eugene.astroexport.references.VaultNoteDescriptor;
import dev.eugene.astroexport.release.ApprovedReleaseMaterializer;
import dev.eugene.astroexport.review.ApprovedPageSnapshot;
import dev.eugene.astroexport.review.ApprovedSnapshotRepository;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SemanticMigrationService {
  private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
  private static final Pattern LINK = Pattern.compile("\\[(?<label>[^\\]\\n]*?)]\\((?<destination>[^\\r\\n)]*?)\\)");

  private final ReferenceMigrationInventory inventoryService;
  private final AtomicExchange atomicExchange;
  private final Clock clock;

  public SemanticMigrationService() {
    this(new ReferenceMigrationInventory(), new JnaAtomicExchange(), Clock.systemUTC());
  }

  SemanticMigrationService(
      ReferenceMigrationInventory inventoryService,
      AtomicExchange atomicExchange,
      Clock clock) {
    this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService");
    this.atomicExchange = Objects.requireNonNull(atomicExchange, "atomicExchange");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public ApplyResult apply(ApplyRequest request, MigrationHooks hooks) {
    Objects.requireNonNull(request, "request");
    MigrationHooks checkedHooks = hooks == null ? MigrationHooks.none() : hooks;
    SemanticOperationLock.Lease lease = null;
    try {
      lease = SemanticOperationLock.acquireExclusive(request.review());
      rejectIncompleteMigration(request.review());
      ApplyPlan plan = stagePlan(request, checkedHooks);
      install(plan, checkedHooks);
      checkedHooks.after(Boundary.LOCK_RELEASE, 1);
      return new ApplyResult(SemanticSchemaState.activationMarker(request.review()));
    } catch (Exception error) {
      throw incomplete(request.review(), error);
    } finally {
      if (lease != null) {
        lease.close();
      }
    }
  }

  public RecoveryResult recover(RecoveryRequest request) {
    Objects.requireNonNull(request, "request");
    SemanticOperationLock.Lease lease = null;
    try {
      lease = SemanticOperationLock.acquireExclusive(request.review());
      Journal journal = readJournal(request.review());
      if (request.mode() == RecoveryMode.ROLL_BACK) {
        rollback(request.review(), journal);
        return new RecoveryResult(RecoveryMode.ROLL_BACK, SemanticSchemaState.migrationJournal(request.review()));
      }
      rollForward(request.review(), journal);
      return new RecoveryResult(RecoveryMode.ROLL_FORWARD, SemanticSchemaState.activationMarker(request.review()));
    } catch (Exception error) {
      throw incomplete(request.review(), error);
    } finally {
      if (lease != null) {
        lease.close();
      }
    }
  }

  private ApplyPlan stagePlan(ApplyRequest request, MigrationHooks hooks) throws IOException {
    ReferenceMigrationInventory.Inventory inventory =
        inventoryService.inspect(request.vault(), request.review(), request.astro(), request.report());
    ReferenceMigrationInventory.DecisionSet decisions =
        inventoryService.validateDecisions(inventory, request.decisions());
    requireDecisionCoverage(inventory, decisions);
    Map<String, CorrectedOrderDecision> correctedOrder = correctedOrderDecisions(
        request.decisions(),
        inventory,
        decisions);
    Path catalog = VaultReferenceCatalog.catalogPath(request.review());
    boolean catalogWasPresent = Files.exists(catalog, LinkOption.NOFOLLOW_LINKS);
    VaultReferenceCatalog reconciledCatalog = VaultReferenceCatalog.loadIfPresent(request.review())
        .reconcile(request.vault(), VaultNoteDescriptor.scan(request.vault()));
    byte[] catalogBytes = reconciledCatalog.write();
    String catalogSha = PageReferenceMapCodec.sha256(catalogBytes);
    Path root = request.review().resolve(".semantic-links");
    Path stagingRoot = root.resolve("staging-v1");
    Path recoveryRoot = root.resolve("recovery-v1");
    recreateDirectory(stagingRoot);
    Files.createDirectories(recoveryRoot);
    forceDirectory(root);
    Path catalogStage = stagingRoot.resolve("catalog-v1.json");
    writeForced(catalogStage, catalogBytes);
    forceDirectory(catalogStage.getParent());

    Journal journal = new Journal(
        "planned",
        inventory.inventorySha256(),
        catalogSha,
        ".semantic-links/recovery-v1",
        new ArrayList<>());
    journal.catalogPublished(request.review().relativize(catalog).toString());
    journal.catalogStaged(request.review().relativize(catalogStage).toString());
    journal.catalogDisplaced(request.review().relativize(recoveryRoot.resolve("catalog-v1.json")).toString());
    journal.catalogWasPresent(catalogWasPresent);
    journal.catalogState("staged");
    writeJournal(request.review(), journal);
    hooks.after(Boundary.CATALOG_STAGED, 1);
    List<PagePlan> pages = new ArrayList<>();
    int index = 0;
    for (ReferenceMigrationAligner.MigrationPage page : inventory.pages()) {
      index++;
      PageIdentity identity = findPage(request.review(), request.vault(), page.sourcePath());
      Path pageStage = stagingRoot.resolve(identity.collection()).resolve(identity.publicId()).resolve("published");
      Files.createDirectories(pageStage);
      byte[] ru = semanticBytes(page.approvedRussian().text(), page, true);
      CorrectedOrderDecision corrected = correctedOrder.get(page.pageRef() + "/order");
      byte[] en = corrected == null
          ? semanticBytes(page.approvedEnglish().text(), page, false)
          : semanticBytes(new String(corrected.bytes(), StandardCharsets.UTF_8), page, false);
      PageReferenceMap references = references(page, ru, en);
      byte[] referenceBytes = PageReferenceMapCodec.write(references);
      PageReferenceMapCodec.validate(
          PageReferenceMapCodec.read(referenceBytes, page.sourcePath() + "/references.json"),
          ru,
          en);
      writeForced(pageStage.resolve("ru.md"), ru);
      writeForced(pageStage.resolve("en.md"), en);
      writeForced(pageStage.resolve("references.json"), referenceBytes);
      forceDirectory(pageStage);
      String stagedSha = PageReferenceMapCodec.sha256(combined(ru, en, referenceBytes));
      Path published = request.review().resolve(identity.collection()).resolve(identity.publicId()).resolve("published");
      Path displaced = recoveryRoot.resolve(identity.collection()).resolve(identity.publicId()).resolve("published");
      JournalPage journalPage = new JournalPage(
          identity.collection(),
          identity.publicId(),
          page.pageRef(),
          page.sourcePath(),
          "staged",
          stagedSha,
          legacySnapshotSha256(published),
          request.review().relativize(published).toString(),
          request.review().relativize(pageStage).toString(),
          request.review().relativize(displaced).toString());
      journal.pages().add(journalPage);
      writeJournal(request.review(), journal);
      hooks.after(Boundary.PAGE_STAGED, index);
      pages.add(new PagePlan(published, pageStage, displaced, journalPage));
    }
    ApplyPlan plan = new ApplyPlan(
        request.vault(),
        request.review(),
        stagingRoot,
        inventory,
        catalog,
        catalogStage,
        recoveryRoot.resolve("catalog-v1.json"),
        journal,
        pages,
        correctedOrder,
        request.astroGate());
    validateStagedCutover(plan);
    hooks.after(Boundary.PARITY_PROJECTED, 1);
    request.astroGate().accept(stagingRoot);
    hooks.after(Boundary.ASTRO_GATED, 1);
    return plan;
  }

  private void install(ApplyPlan plan, MigrationHooks hooks) throws IOException {
    int index = 0;
    for (PagePlan page : plan.pages()) {
      index++;
      Files.createDirectories(page.displaced().getParent());
      page.journalPage().state("installing");
      writeJournal(plan.review(), plan.journal());
      atomicExchange.exchange(page.published(), page.staged());
      forceDirectory(page.published().getParent());
      page.journalPage().state("installed");
      writeJournal(plan.review(), plan.journal());
      hooks.after(Boundary.PAGE_INSTALLED, index);
    }
    for (PagePlan page : plan.pages()) {
      if (!stagedHashMatches(page.published(), page.journalPage().stagedSha256())) {
        throw new IllegalStateException("installed semantic page hash mismatch: " + page.published());
      }
      page.journalPage().state("verified");
      writeJournal(plan.review(), plan.journal());
      Files.move(page.staged(), page.displaced(), StandardCopyOption.ATOMIC_MOVE);
      forceDirectory(page.displaced().getParent());
    }
    plan.journal().catalogState("installing");
    writeJournal(plan.review(), plan.journal());
    if (plan.journal().catalogWasPresent()) {
      atomicExchange.exchange(plan.catalogPublished(), plan.catalogStaged());
    } else {
      Files.createDirectories(plan.catalogPublished().getParent());
      Files.move(plan.catalogStaged(), plan.catalogPublished(), StandardCopyOption.ATOMIC_MOVE);
    }
    forceDirectory(plan.catalogPublished().getParent());
    plan.journal().catalogState("installed");
    writeJournal(plan.review(), plan.journal());
    hooks.after(Boundary.CATALOG_INSTALLED, 1);
    if (!PageReferenceMapCodec.sha256(readSafe(plan.catalogPublished()))
        .equals(plan.journal().catalogSha256())) {
      throw new IllegalStateException("installed catalog hash mismatch: " + plan.catalogPublished());
    }
    if (plan.journal().catalogWasPresent()) {
      Files.move(plan.catalogStaged(), plan.catalogDisplaced(), StandardCopyOption.ATOMIC_MOVE);
      forceDirectory(plan.catalogDisplaced().getParent());
    }
    plan.journal().catalogState("complete");
    writeJournal(plan.review(), plan.journal());
    plan.journal().state("cleanup-pending");
    writeJournal(plan.review(), plan.journal());
    writeActivationMarker(plan.review(), plan.journal());
    hooks.after(Boundary.MARKER_WRITE, 1);
    plan.journal().state("complete");
    for (JournalPage page : plan.journal().pages()) {
      page.state("complete");
    }
    plan.journal().catalogState("complete");
    writeJournal(plan.review(), plan.journal());
    hooks.after(Boundary.JOURNAL_FORCED, 1);
    for (PagePlan page : plan.pages()) {
      try {
        deleteTree(page.displaced());
        page.journalPage().state("complete");
      } catch (IOException cleanup) {
        page.journalPage().state("cleanup-pending");
      }
    }
    writeJournal(plan.review(), plan.journal());
    hooks.after(Boundary.DISPLACED_CLEANUP, 1);
  }

  private void rollForward(Path review, Journal journal) throws IOException {
    for (JournalPage page : journal.pages()) {
      Path published = review.resolve(page.published());
      Path staged = review.resolve(page.staged());
      if ("staged".equals(page.state())) {
        if (!stagedHashMatches(staged, page.stagedSha256())) {
          throw new IllegalStateException("staged recovery hash mismatch: " + staged);
        }
        atomicExchange.exchange(published, staged);
        Files.createDirectories(review.resolve(page.displaced()).getParent());
        Files.move(staged, review.resolve(page.displaced()), StandardCopyOption.ATOMIC_MOVE);
      } else if ("installing".equals(page.state())) {
        if (stagedHashMatches(published, page.stagedSha256())) {
          Files.createDirectories(review.resolve(page.displaced()).getParent());
          Files.move(staged, review.resolve(page.displaced()), StandardCopyOption.ATOMIC_MOVE);
        } else if (stagedHashMatches(staged, page.stagedSha256())) {
          atomicExchange.exchange(published, staged);
          Files.createDirectories(review.resolve(page.displaced()).getParent());
          Files.move(staged, review.resolve(page.displaced()), StandardCopyOption.ATOMIC_MOVE);
        } else {
          throw new IllegalStateException("installing page recovery hash mismatch: " + published);
        }
      } else if ("installed".equals(page.state()) || "verified".equals(page.state())) {
        if (!stagedHashMatches(published, page.stagedSha256())) {
          throw new IllegalStateException("installed recovery hash mismatch: " + published);
        }
      }
      page.state("complete");
    }
    rollForwardCatalog(review, journal);
    journal.state("complete");
    journal.catalogState("complete");
    writeActivationMarker(review, journal);
    writeJournal(review, journal);
  }

  private void rollback(Path review, Journal journal) throws IOException {
    List<JournalPage> pages = new ArrayList<>(journal.pages());
    pages.sort(Comparator.comparing(JournalPage::published).reversed());
    for (JournalPage page : pages) {
      requireRollbackRecoverable(review, page);
    }
    for (JournalPage page : pages) {
      Path published = review.resolve(page.published());
      Path displaced = review.resolve(page.displaced());
      Path staged = review.resolve(page.staged());
      if (legacySnapshotMatches(displaced, page.legacySha256())
          && stagedHashMatches(published, page.stagedSha256())) {
        atomicExchange.exchange(published, displaced);
        forceDirectory(published.getParent());
        deleteTree(displaced);
      } else if (legacySnapshotMatches(displaced, page.legacySha256())) {
        Files.createDirectories(published.getParent());
        Files.move(displaced, published, StandardCopyOption.ATOMIC_MOVE);
      } else if (legacySnapshotMatches(staged, page.legacySha256())
          && stagedHashMatches(published, page.stagedSha256())) {
        atomicExchange.exchange(published, staged);
        forceDirectory(published.getParent());
        deleteTree(staged);
      } else if (legacySnapshotMatches(published, page.legacySha256())
          && stagedHashMatches(staged, page.stagedSha256())) {
        deleteTree(staged);
      }
      requireLegacySnapshot(published, page.legacySha256());
    }
    rollbackCatalog(review, journal);
    Files.deleteIfExists(SemanticSchemaState.activationMarker(review));
    Path journalPath = SemanticSchemaState.migrationJournal(review);
    Path rollbackEvidence = journalPath.resolveSibling("migration-v1.rollback-complete.json");
    writeJson(rollbackEvidence, journal.toPayload());
    Files.deleteIfExists(journalPath);
    forceDirectory(journalPath.getParent());
  }

  private void rollForwardCatalog(Path review, Journal journal) throws IOException {
    Path published = review.resolve(journal.catalogPublished());
    Path staged = review.resolve(journal.catalogStaged());
    Path displaced = review.resolve(journal.catalogDisplaced());
    String state = journal.catalogState();
    if ("staged".equals(state)) {
      requireCatalogHash(staged, journal.catalogSha256());
      if (journal.catalogWasPresent()) {
        atomicExchange.exchange(published, staged);
        Files.createDirectories(displaced.getParent());
        Files.move(staged, displaced, StandardCopyOption.ATOMIC_MOVE);
      } else {
        Files.createDirectories(published.getParent());
        Files.move(staged, published, StandardCopyOption.ATOMIC_MOVE);
      }
    } else if ("installing".equals(state)) {
      if (catalogHashMatches(published, journal.catalogSha256())) {
        if (journal.catalogWasPresent()) {
          Files.createDirectories(displaced.getParent());
          Files.move(staged, displaced, StandardCopyOption.ATOMIC_MOVE);
        }
      } else {
        requireCatalogHash(staged, journal.catalogSha256());
        if (journal.catalogWasPresent()) {
          atomicExchange.exchange(published, staged);
          Files.createDirectories(displaced.getParent());
          Files.move(staged, displaced, StandardCopyOption.ATOMIC_MOVE);
        } else {
          Files.createDirectories(published.getParent());
          Files.move(staged, published, StandardCopyOption.ATOMIC_MOVE);
        }
      }
    } else if ("installed".equals(state) || "complete".equals(state)) {
      requireCatalogHash(published, journal.catalogSha256());
      if (journal.catalogWasPresent() && Files.exists(staged, LinkOption.NOFOLLOW_LINKS)
          && !Files.exists(displaced, LinkOption.NOFOLLOW_LINKS)) {
        Files.createDirectories(displaced.getParent());
        Files.move(staged, displaced, StandardCopyOption.ATOMIC_MOVE);
      }
    }
  }

  private void rollbackCatalog(Path review, Journal journal) throws IOException {
    Path published = review.resolve(journal.catalogPublished());
    Path staged = review.resolve(journal.catalogStaged());
    Path displaced = review.resolve(journal.catalogDisplaced());
    if (!journal.catalogWasPresent()) {
      if (catalogHashMatches(published, journal.catalogSha256())) {
        Files.delete(published);
      } else if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
        requireCatalogHash(staged, journal.catalogSha256());
        Files.delete(staged);
      } else if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("cannot roll back unverified bootstrap catalog: " + published);
      }
      return;
    }
    if (Files.exists(displaced, LinkOption.NOFOLLOW_LINKS)
        && Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
      atomicExchange.exchange(published, displaced);
    } else if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)
        && Files.exists(published, LinkOption.NOFOLLOW_LINKS)
        && ("installing".equals(journal.catalogState())
            || "installed".equals(journal.catalogState())
            || "complete".equals(journal.catalogState()))) {
      atomicExchange.exchange(published, staged);
    } else if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
      Files.delete(staged);
    }
  }

  private static byte[] semanticBytes(
      String markdown,
      ReferenceMigrationAligner.MigrationPage page,
      boolean russian) {
    List<ReferenceMigrationAligner.MigrationOccurrence> occurrences = page.occurrences();
    if (occurrences.isEmpty()) {
      return markdown.getBytes(StandardCharsets.UTF_8);
    }
    String converted = markdown;
    for (ReferenceMigrationAligner.MigrationOccurrence occurrence : occurrences) {
      String destination = russian
          ? occurrence.proposedEnDestination().replace("/en/", "/ru/")
          : occurrence.proposedEnDestination();
      converted = replaceFirstDestination(
          converted,
          destination,
          "ref:" + referenceId(occurrence)
              + (occurrence.heading() == null || occurrence.heading().isBlank()
                  ? ""
                  : occurrence.heading()));
    }
    return converted.getBytes(StandardCharsets.UTF_8);
  }

  private static String replaceFirstDestination(
      String markdown,
      String expectedDestination,
      String replacementDestination) {
    Matcher matcher = LINK.matcher(markdown);
    StringBuilder result = new StringBuilder(markdown.length());
    int cursor = 0;
    while (matcher.find()) {
      if (!expectedDestination.equals(matcher.group("destination"))) {
        continue;
      }
      result.append(markdown, cursor, matcher.start("destination"));
      result.append(replacementDestination);
      result.append(markdown.substring(matcher.end("destination")));
      return result.toString();
    }
    throw new IllegalStateException("cannot replace legacy destination: " + expectedDestination);
  }

  private static void requireDecisionCoverage(
      ReferenceMigrationInventory.Inventory inventory,
      ReferenceMigrationInventory.DecisionSet decisions) {
    List<String> accepted = decisions.keys();
    List<String> missing = new ArrayList<>();
    for (ReferenceMigrationAligner.MigrationPage page : inventory.pages()) {
      if (page.automatic()) {
        continue;
      }
      if (page.status() == ReferenceMigrationAligner.PageStatus.ORDER_MISMATCH_PAGE) {
        String key = page.pageRef() + "/order";
        if (!accepted.contains(key)) {
          missing.add(key);
        }
        continue;
      }
      for (ReferenceMigrationAligner.MigrationOccurrence occurrence : page.occurrences()) {
        if (!accepted.contains(occurrence.occurrenceKey())) {
          missing.add(occurrence.occurrenceKey());
        }
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException("missing migration decisions: " + missing);
    }
  }

  private static Map<String, CorrectedOrderDecision> correctedOrderDecisions(
      Path decisionsPath,
      ReferenceMigrationInventory.Inventory inventory,
      ReferenceMigrationInventory.DecisionSet decisions) throws IOException {
    Set<String> accepted = Set.copyOf(decisions.keys());
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = JSON.readValue(Files.readAllBytes(decisionsPath), Map.class);
    Object raw = payload.get("decisions");
    if (!(raw instanceof Map<?, ?> rawDecisions)) {
      return Map.of();
    }
    LinkedHashMap<String, CorrectedOrderDecision> corrected = new LinkedHashMap<>();
    for (ReferenceMigrationAligner.MigrationPage page : inventory.pages()) {
      String key = page.pageRef() + "/order";
      if (!accepted.contains(key)) {
        continue;
      }
      Object rawDecision = rawDecisions.get(key);
      if (!(rawDecision instanceof Map<?, ?> decision)
          || !"approve-corrected-order".equals(decision.get("decision"))) {
        continue;
      }
      String relative = String.valueOf(decision.get("correctedEnglishPath"));
      Path correctedPath = resolveDecisionSibling(decisionsPath, relative);
      byte[] bytes = Files.readAllBytes(correctedPath);
      corrected.put(key, new CorrectedOrderDecision(bytes));
    }
    return Map.copyOf(corrected);
  }

  private static Path resolveDecisionSibling(Path decisionsPath, String relative) {
    Path candidate = Path.of(relative);
    if (candidate.isAbsolute()) {
      throw new IllegalStateException("correctedEnglishPath must be relative");
    }
    Path base = decisionsPath.toAbsolutePath().normalize().getParent();
    Path resolved = base.resolve(candidate).normalize();
    if (!resolved.startsWith(base)) {
      throw new IllegalStateException("correctedEnglishPath escapes decision directory");
    }
    return resolved;
  }

  private static void validateStagedCutover(ApplyPlan plan) throws IOException {
    Journal durableJournal = readJournal(plan.review());
    for (JournalPage page : durableJournal.pages()) {
      Path staged = plan.review().resolve(page.staged());
      if (!stagedHashMatches(staged, page.stagedSha256())) {
        throw new IllegalStateException("staged semantic page hash mismatch: " + staged);
      }
    }
    requireCatalogHash(plan.catalogStaged(), durableJournal.catalogSha256());
    validateMaterializedReleaseParity(plan, durableJournal);
  }

  private static void validateMaterializedReleaseParity(ApplyPlan plan, Journal durableJournal) throws IOException {
    ApprovedSnapshotRepository repository = new ApprovedSnapshotRepository();
    SelectionResult selection = selectionFromJournal(durableJournal);
    VaultReferenceCatalog stagedCatalog = VaultReferenceCatalog.read(readSafe(plan.catalogStaged()));
    List<ApprovedPageSnapshot> staged = repository.loadSelected(selection, plan.stagingRoot(), stagedCatalog);
    ManifestResult semantic = new ApprovedReleaseMaterializer()
        .materialize(staged, plan.vault())
        .manifest();

    Map<String, ManifestEntry> expectedRussian = new LinkedHashMap<>();
    Map<String, ManifestEntry> expectedEnglish = new LinkedHashMap<>();
    Map<String, JournalPage> journalPages = pagesBySource(durableJournal);
    for (ReferenceMigrationAligner.MigrationPage page : plan.inventory().pages()) {
      JournalPage journalPage = journalPages.get(page.sourcePath());
      if (journalPage == null) {
        throw new IllegalStateException("materialized release parity missing journal page for " + page.sourcePath());
      }
      expectedRussian.put(page.sourcePath(), approvedEntry(
          journalPage.collection(),
          page.sourcePath(),
          "ru",
          page.approvedRussian().text()));
      CorrectedOrderDecision corrected = plan.correctedOrder().get(page.pageRef() + "/order");
      String english = corrected == null
          ? page.approvedEnglish().text()
          : new String(corrected.bytes(), StandardCharsets.UTF_8);
      expectedEnglish.put(page.sourcePath(), approvedEntry(
          journalPage.collection(),
          page.sourcePath(),
          "en",
          english));
    }
    requireEntriesMatch(expectedRussian, semantic.entries(), "ru");
    requireEntriesMatch(expectedEnglish, semantic.englishEntries(), "en");
  }

  private static Map<String, JournalPage> pagesBySource(Journal journal) {
    LinkedHashMap<String, JournalPage> pages = new LinkedHashMap<>();
    for (JournalPage page : journal.pages()) {
      pages.put(page.sourcePath(), page);
    }
    return Map.copyOf(pages);
  }

  private static ManifestEntry approvedEntry(
      String collection,
      String sourcePath,
      String language,
      String markdown) {
    ApprovedMarkdown approved = parseApprovedMarkdown(sourcePath, markdown);
    String publicId = String.valueOf(approved.metadata().get("id"));
    String reviewType = String.valueOf(approved.metadata().getOrDefault(
        "reviewType",
        approved.metadata().getOrDefault("publicContentType", "note")));
    return new ManifestEntry(
        sourcePath,
        approvedTargetPath(collection, publicId, language),
        approvedRoute(collection, publicId, reviewType, language),
        new LinkedHashMap<>(approved.metadata()),
        approved.body());
  }

  private static String approvedTargetPath(String collection, String publicId, String language) {
    if ("editorial".equals(collection)) {
      return "src/data/pages/" + language + "/" + publicId + ".json";
    }
    return "src/content/" + collection + "/" + language + "/" + publicId + ".md";
  }

  private static ApprovedMarkdown parseApprovedMarkdown(String sourcePath, String markdown) {
    if (!markdown.startsWith("---\n") && !markdown.startsWith("---\r\n")) {
      return new ApprovedMarkdown(Map.of(), markdown);
    }
    int firstNewline = markdown.indexOf('\n');
    int metadataStart = firstNewline + 1;
    int closing = findClosingDelimiter(markdown, metadataStart);
    if (closing < 0) {
      return new ApprovedMarkdown(Map.of(), markdown);
    }
    int bodyStart = markdown.indexOf('\n', closing);
    String body = bodyStart < 0 ? "" : markdown.substring(bodyStart + 1);
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> metadata = (Map<String, Object>) new org.snakeyaml.engine.v2.api.Load(
          org.snakeyaml.engine.v2.api.LoadSettings.builder()
              .setLabel(sourcePath)
              .build())
          .loadFromString(markdown.substring(metadataStart, closing));
      return new ApprovedMarkdown(metadata == null ? Map.of() : metadata, body);
    } catch (RuntimeException error) {
      throw new IllegalStateException("invalid approved parity frontmatter: " + sourcePath, error);
    }
  }

  private static int findClosingDelimiter(String markdown, int start) {
    int lineStart = start;
    while (lineStart < markdown.length()) {
      int lineEnd = markdown.indexOf('\n', lineStart);
      int contentEnd = lineEnd < 0 ? markdown.length() : lineEnd;
      if (contentEnd > lineStart && markdown.charAt(contentEnd - 1) == '\r') {
        contentEnd--;
      }
      if ("---".equals(markdown.substring(lineStart, contentEnd))) {
        return lineStart;
      }
      if (lineEnd < 0) {
        break;
      }
      lineStart = lineEnd + 1;
    }
    return -1;
  }

  private static String approvedRoute(
      String collection,
      String publicId,
      String contentType,
      String language) {
    if ("editorial".equals(collection)) {
      return "home".equals(publicId) ? "/" + language + "/" : "/" + language + "/" + publicId + "/";
    }
    String section = switch (contentType) {
      case "essay" -> "essays";
      case "claim" -> "claims";
      case "note" -> "notes";
      case "album" -> "music";
      case "book" -> "library";
      case "concept" -> "concepts";
      default -> throw new IllegalStateException("unsupported approved reviewType " + contentType);
    };
    return "/" + language + "/" + section + "/" + publicId + "/";
  }

  private static SelectionResult selectionFromJournal(Journal journal) {
    List<Note> notes = journal.pages().stream()
        .map(page -> new Note(
            Path.of(page.sourcePath()),
            page.sourcePath(),
            page.publicId(),
            Map.of(),
            "",
            true,
            page.publicId(),
            page.collection(),
            "essay",
            List.of()))
        .toList();
    return new SelectionResult(notes, List.of(), notes.size(), notes.size());
  }

  private static void requireEntriesMatch(
      Map<String, ManifestEntry> expected,
      List<ManifestEntry> actual,
      String language) {
    Map<String, ManifestEntry> observed = new LinkedHashMap<>();
    for (ManifestEntry entry : actual) {
      observed.put(entry.sourcePath(), entry);
    }
    if (!expected.keySet().equals(observed.keySet())) {
      throw new IllegalStateException(
          "materialized release parity mismatch for " + language + " sources: expected "
              + expected.keySet()
              + " but staged produced "
              + observed.keySet());
    }
    for (Map.Entry<String, ManifestEntry> entry : expected.entrySet()) {
      ManifestEntry expectedEntry = entry.getValue();
      ManifestEntry actualEntry = observed.get(entry.getKey());
      String difference = releaseEntryDifference(expectedEntry, actualEntry);
      if (difference != null) {
        throw new IllegalStateException(
            "materialized release parity mismatch for "
                + language
                + " "
                + entry.getKey()
                + ": "
                + difference);
      }
    }
  }

  private static String releaseEntryDifference(ManifestEntry expected, ManifestEntry actual) {
    if (!expected.sourcePath().equals(actual.sourcePath())) {
      return "sourcePath expected " + expected.sourcePath() + " but was " + actual.sourcePath();
    }
    if (!expected.targetPath().equals(actual.targetPath())) {
      return "targetPath expected " + expected.targetPath() + " but was " + actual.targetPath();
    }
    if (!expected.route().equals(actual.route())) {
      return "route expected " + expected.route() + " but was " + actual.route();
    }
    if (!expected.metadata().equals(actual.metadata())) {
      return "metadata expected " + expected.metadata() + " but was " + actual.metadata();
    }
    if (!expected.body().stripTrailing().equals(actual.body().stripTrailing())) {
      return "body expected " + expected.body() + " but was " + actual.body();
    }
    return null;
  }

  private static String approvedBody(byte[] markdown) {
    String text = new String(markdown, StandardCharsets.UTF_8);
    if (!text.startsWith("---")) {
      return text;
    }
    int headerEnd = text.indexOf("\n---", 3);
    if (headerEnd < 0) {
      return text;
    }
    int bodyStart = headerEnd + "\n---".length();
    if (bodyStart < text.length() && text.charAt(bodyStart) == '\r') {
      bodyStart++;
    }
    if (bodyStart < text.length() && text.charAt(bodyStart) == '\n') {
      bodyStart++;
    }
    return text.substring(bodyStart);
  }

  private static PageReferenceMap references(
      ReferenceMigrationAligner.MigrationPage page,
      byte[] ru,
      byte[] en) {
    LinkedHashMap<String, PageReferenceMap.Reference> references = new LinkedHashMap<>();
    List<String> order = page.occurrences().stream()
        .map(SemanticMigrationService::referenceId)
        .toList();
    for (ReferenceMigrationAligner.MigrationOccurrence occurrence : page.occurrences()) {
      references.put(referenceId(occurrence), reference(occurrence));
    }
    return new PageReferenceMap(
        PageReferenceMap.SCHEMA_VERSION,
        page.pageRef(),
        page.sourcePath(),
        PageReferenceMapCodec.sha256(ru),
        PageReferenceMapCodec.sha256(en),
        order,
        references);
  }

  private static String referenceId(ReferenceMigrationAligner.MigrationOccurrence occurrence) {
    if (occurrence.proposedReferenceId() != null && !occurrence.proposedReferenceId().isBlank()) {
      return occurrence.proposedReferenceId();
    }
    return "ref-%04d".formatted(occurrence.sourceOrdinal());
  }

  private static PageReferenceMap.Reference reference(
      ReferenceMigrationAligner.MigrationOccurrence occurrence) {
    if (occurrence.proposedReference() != null) {
      return occurrence.proposedReference();
    }
    LinkParts parts = parseRawWikilink(occurrence.rawWikilink());
    return new PageReferenceMap.Reference(
        occurrence.targetRef(),
        parts.authoredTarget(),
        occurrence.heading() == null ? "" : occurrence.heading(),
        parts.label());
  }

  private static LinkParts parseRawWikilink(String rawWikilink) {
    if (rawWikilink == null || !rawWikilink.startsWith("[[") || !rawWikilink.endsWith("]]")) {
      return new LinkParts("", "");
    }
    String inner = rawWikilink.substring(2, rawWikilink.length() - 2);
    int pipe = inner.indexOf('|');
    String target = pipe < 0 ? inner : inner.substring(0, pipe);
    String label = pipe < 0 ? target : inner.substring(pipe + 1);
    int heading = target.indexOf('#');
    if (heading >= 0) {
      target = target.substring(0, heading);
    }
    return new LinkParts(target, label);
  }

  private static PageIdentity findPage(Path review, Path vault, String sourcePath) throws IOException {
    PageIdentity fromVault = sourceIdentity(vault, sourcePath);
    if (fromVault != null
        && Files.exists(review.resolve(fromVault.collection()).resolve(fromVault.publicId()).resolve("published"),
            LinkOption.NOFOLLOW_LINKS)) {
      return fromVault;
    }
    try (var collections = Files.list(review)) {
      for (Path collection : collections
          .filter(path -> !path.getFileName().toString().startsWith("."))
          .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .sorted()
          .toList()) {
        try (var pages = Files.list(collection)) {
          for (Path page : pages
              .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
              .sorted()
              .toList()) {
            Path published = page.resolve("published");
            if (!Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
              continue;
            }
            if (sourcePath.equals(sourcePathFrom(published))) {
              return new PageIdentity(
                  collection.getFileName().toString(),
                  page.getFileName().toString());
            }
          }
        }
      }
    }
    throw new IllegalStateException("no published approved pair for " + sourcePath);
  }

  private static PageIdentity sourceIdentity(Path vault, String sourcePath) throws IOException {
    Path source = vault.toAbsolutePath().normalize().resolve(sourcePath).normalize();
    if (!source.startsWith(vault.toAbsolutePath().normalize()) || !Files.isRegularFile(source)) {
      return null;
    }
    try {
      var document = dev.eugene.astroexport.frontmatter.FrontmatterDocument.parse(
          source, sourcePath, Files.readString(source, StandardCharsets.UTF_8));
      Object collection = document.metadata().get("publicCollection");
      Object publicId = document.metadata().get("publicId");
      if (collection instanceof String collectionText && !collectionText.isBlank()
          && publicId instanceof String publicIdText && !publicIdText.isBlank()) {
        return new PageIdentity(collectionText.strip(), publicIdText.strip());
      }
    } catch (RuntimeException ignored) {
      // The inventory has already made invalid source metadata unsafe.
    }
    return null;
  }

  private static String sourcePathFrom(Path published) {
    Path references = published.resolve("references.json");
    if (Files.exists(references, LinkOption.NOFOLLOW_LINKS)) {
      try {
        return PageReferenceMapCodec.read(
            Files.readAllBytes(references),
            references.toString()).sourcePath();
      } catch (IOException | RuntimeException ignored) {
        // Legacy pairs may have absent or stale sidecars.
      }
    }
    return published.getParent().getFileName() + ".md";
  }

  private static boolean stagedHashMatches(Path root, String expected) {
    try {
      byte[] ru = readSafe(root.resolve("ru.md"));
      byte[] en = readSafe(root.resolve("en.md"));
      byte[] references = readSafe(root.resolve("references.json"));
      return expected.equals(PageReferenceMapCodec.sha256(combined(ru, en, references)));
    } catch (IOException error) {
      return false;
    }
  }

  private static String legacySnapshotSha256(Path root) throws IOException {
    return PageReferenceMapCodec.sha256(legacySnapshotBytes(root));
  }

  private static boolean legacySnapshotMatches(Path root, String expected) {
    try {
      return expected != null && expected.equals(legacySnapshotSha256(root));
    } catch (IOException error) {
      return false;
    }
  }

  private static void requireLegacySnapshot(Path root, String expected) throws IOException {
    if (!legacySnapshotMatches(root, expected)) {
      throw new IllegalStateException("legacy recovery hash mismatch: " + root);
    }
  }

  private static byte[] legacySnapshotBytes(Path root) throws IOException {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("legacy snapshot is not a directory: " + root);
    }
    List<String> names;
    try (var entries = Files.list(root)) {
      names = entries.map(path -> path.getFileName().toString()).sorted().toList();
    }
    if (!names.equals(List.of("en.md", "ru.md"))
        && !names.equals(List.of("en.md", "references.json", "ru.md"))) {
      throw new IOException("legacy snapshot has unexpected files: " + root);
    }
    List<byte[]> parts = new ArrayList<>();
    for (String name : names) {
      parts.add(name.getBytes(StandardCharsets.UTF_8));
      parts.add(readSafe(root.resolve(name)));
    }
    return combined(parts.toArray(byte[][]::new));
  }

  private static void requireRollbackRecoverable(Path review, JournalPage page) throws IOException {
    Path published = review.resolve(page.published());
    Path staged = review.resolve(page.staged());
    Path displaced = review.resolve(page.displaced());
    if (legacySnapshotMatches(published, page.legacySha256())
        || legacySnapshotMatches(staged, page.legacySha256())
        || legacySnapshotMatches(displaced, page.legacySha256())) {
      return;
    }
    throw new IllegalStateException("cannot roll back without verified legacy snapshot: " + published);
  }

  private static void requireCatalogHash(Path path, String expected) throws IOException {
    if (!catalogHashMatches(path, expected)) {
      throw new IllegalStateException("catalog hash mismatch: " + path);
    }
  }

  private static boolean catalogHashMatches(Path path, String expected) {
    try {
      return expected.equals(PageReferenceMapCodec.sha256(readSafe(path)));
    } catch (IOException error) {
      return false;
    }
  }

  private static byte[] combined(byte[]... parts) {
    int size = 0;
    for (byte[] part : parts) {
      size += part.length + 1;
    }
    ByteBuffer buffer = ByteBuffer.allocate(size);
    for (byte[] part : parts) {
      buffer.put(part);
      buffer.put((byte) 0);
    }
    return buffer.array();
  }

  private static byte[] readSafe(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
      throw new IOException("expected regular file: " + path);
    }
    return Files.readAllBytes(path);
  }

  private static void writeActivationMarker(Path review, Journal journal) throws IOException {
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", 1);
    payload.put("inventorySha256", journal.inventorySha256());
    payload.put("catalogSha256", journal.catalogSha256());
    payload.put("activatedAt", clockInstant(journal));
    writeJson(SemanticSchemaState.activationMarker(review), payload);
  }

  private static String clockInstant(Journal journal) {
    Object activatedAt = journal.extra().get("activatedAt");
    return activatedAt instanceof String text ? text : Instant.EPOCH.toString();
  }

  private static Journal readJournal(Path review) throws IOException {
    Map<String, Object> payload = JSON.readValue(
        Files.readAllBytes(SemanticSchemaState.migrationJournal(review)),
        new TypeReference<>() { });
    return Journal.fromPayload(payload);
  }

  private void writeJournal(Path review, Journal journal) throws IOException {
    journal.extra().put("activatedAt", clock.instant().toString());
    writeJson(SemanticSchemaState.migrationJournal(review), journal.toPayload());
  }

  private static void writeJson(Path destination, Map<String, Object> payload)
      throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(payload);
    Path parent = destination.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path temporary = Files.createTempFile(parent, "." + destination.getFileName(), ".tmp");
    try {
      writeForced(temporary, bytes);
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      if (parent != null) {
        forceDirectory(parent);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void writeForced(Path path, byte[] bytes) throws IOException {
    try (FileChannel channel = FileChannel.open(
        path,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
  }

  private static void recreateDirectory(Path path) throws IOException {
    deleteTree(path);
    Files.createDirectories(path);
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static void forceDirectory(Path directory) throws IOException {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (UnsupportedOperationException ignored) {
      // Some test filesystems do not expose directory fsync.
    }
  }

  private static MigrationIncompleteException incomplete(Path review, Exception error) {
    if (error instanceof MigrationIncompleteException incomplete) {
      return incomplete;
    }
    String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    return new MigrationIncompleteException(
        "semantic migration incomplete: "
            + reason
            + "; journal="
            + SemanticSchemaState.migrationJournal(review)
            + " recovery="
            + review.resolve(".semantic-links/recovery-v1"),
        error);
  }

  private static void rejectIncompleteMigration(Path review) {
    if (Files.exists(SemanticSchemaState.migrationJournal(review), LinkOption.NOFOLLOW_LINKS)
        && SemanticSchemaState.mode(review) == SemanticSchemaState.Mode.MIGRATION_INCOMPLETE) {
      throw new MigrationIncompleteException(
          "semantic migration already incomplete; use --roll-forward or --roll-back before --apply; journal="
              + SemanticSchemaState.migrationJournal(review)
              + " recovery="
              + review.resolve(".semantic-links/recovery-v1"),
          null);
    }
  }

  public record ApplyRequest(
      Path vault,
      Path review,
      Path astro,
      Path report,
      Path decisions,
      Consumer<Path> astroGate) {
    public ApplyRequest(Path vault, Path review, Path astro, Path report, Path decisions) {
      this(vault, review, astro, report, decisions, path -> { });
    }

    public ApplyRequest {
      astroGate = astroGate == null ? path -> { } : astroGate;
    }
  }

  public record ApplyResult(Path activationMarker) { }

  public record RecoveryRequest(Path review, RecoveryMode mode) {
    public static RecoveryRequest rollForward(Path review) {
      return new RecoveryRequest(review, RecoveryMode.ROLL_FORWARD);
    }

    public static RecoveryRequest rollBack(Path review) {
      return new RecoveryRequest(review, RecoveryMode.ROLL_BACK);
    }
  }

  public record RecoveryResult(RecoveryMode mode, Path evidence) { }

  public enum RecoveryMode {
    ROLL_FORWARD,
    ROLL_BACK
  }

  public enum Boundary {
    CATALOG_STAGED,
    CATALOG_INSTALLED,
    PAGE_STAGED,
    PAGE_INSTALLED,
    PARITY_PROJECTED,
    ASTRO_GATED,
    MARKER_WRITE,
    JOURNAL_FORCED,
    DISPLACED_CLEANUP,
    LOCK_RELEASE
  }

  public interface MigrationHooks {
    void after(Boundary boundary, int count) throws IOException;

    static MigrationHooks none() {
      return (boundary, count) -> { };
    }

    static MigrationHooks failOn(Boundary boundary, int count) {
      return (observed, observedCount) -> {
        if (observed == boundary && observedCount == count) {
          throw new IOException("injected failure after " + boundary);
        }
      };
    }
  }

  public static final class MigrationIncompleteException extends RuntimeException {
    public MigrationIncompleteException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private record ApplyPlan(
      Path vault,
      Path review,
      Path stagingRoot,
      ReferenceMigrationInventory.Inventory inventory,
      Path catalogPublished,
      Path catalogStaged,
      Path catalogDisplaced,
      Journal journal,
      List<PagePlan> pages,
      Map<String, CorrectedOrderDecision> correctedOrder,
      Consumer<Path> astroGate) { }

  private record PagePlan(
      Path published,
      Path staged,
      Path displaced,
      JournalPage journalPage) { }

  private record PageIdentity(String collection, String publicId) { }

  private record CorrectedOrderDecision(byte[] bytes) {
    private CorrectedOrderDecision {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  private record LinkParts(String authoredTarget, String label) { }

  private record ApprovedMarkdown(Map<String, Object> metadata, String body) {
    private ApprovedMarkdown {
      metadata = Map.copyOf(metadata);
    }
  }

  private static final class Journal {
    private String state;
    private final String inventorySha256;
    private final String catalogSha256;
    private final String recoveryRoot;
    private final List<JournalPage> pages;
    private final Map<String, Object> extra = new LinkedHashMap<>();
    private String catalogState;
    private String catalogPublished;
    private String catalogStaged;
    private String catalogDisplaced;
    private boolean catalogWasPresent;

    private Journal(
        String state,
        String inventorySha256,
        String catalogSha256,
        String recoveryRoot,
        List<JournalPage> pages) {
      this.state = state;
      this.inventorySha256 = inventorySha256;
      this.catalogSha256 = catalogSha256;
      this.recoveryRoot = recoveryRoot;
      this.pages = pages;
    }

    private String inventorySha256() {
      return inventorySha256;
    }

    private String catalogSha256() {
      return catalogSha256;
    }

    private List<JournalPage> pages() {
      return pages;
    }

    private Map<String, Object> extra() {
      return extra;
    }

    private void state(String state) {
      this.state = state;
    }

    private String catalogState() {
      return catalogState;
    }

    private void catalogState(String catalogState) {
      this.catalogState = catalogState;
    }

    private String catalogPublished() {
      return catalogPublished;
    }

    private void catalogPublished(String catalogPublished) {
      this.catalogPublished = catalogPublished;
    }

    private String catalogStaged() {
      return catalogStaged;
    }

    private void catalogStaged(String catalogStaged) {
      this.catalogStaged = catalogStaged;
    }

    private String catalogDisplaced() {
      return catalogDisplaced;
    }

    private void catalogDisplaced(String catalogDisplaced) {
      this.catalogDisplaced = catalogDisplaced;
    }

    private boolean catalogWasPresent() {
      return catalogWasPresent;
    }

    private void catalogWasPresent(boolean catalogWasPresent) {
      this.catalogWasPresent = catalogWasPresent;
    }

    private Map<String, Object> toPayload() {
      LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
      payload.put("schemaVersion", 1);
      payload.put("state", state);
      payload.put("inventorySha256", inventorySha256);
      payload.put("catalogSha256", catalogSha256);
      payload.put("catalogState", catalogState);
      payload.put("catalogPublished", catalogPublished);
      payload.put("catalogStaged", catalogStaged);
      payload.put("catalogDisplaced", catalogDisplaced);
      payload.put("catalogWasPresent", catalogWasPresent);
      payload.put("recoveryRoot", recoveryRoot);
      payload.put("pages", pages.stream().map(JournalPage::toPayload).toList());
      payload.putAll(extra);
      return payload;
    }

    private static Journal fromPayload(Map<String, Object> payload) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> rawPages = (List<Map<String, Object>>) payload.get("pages");
      Journal journal = new Journal(
          (String) payload.get("state"),
          (String) payload.get("inventorySha256"),
          (String) payload.get("catalogSha256"),
          (String) payload.get("recoveryRoot"),
          rawPages.stream().map(JournalPage::fromPayload).collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
      journal.catalogState((String) payload.get("catalogState"));
      journal.catalogPublished((String) payload.get("catalogPublished"));
      journal.catalogStaged((String) payload.get("catalogStaged"));
      journal.catalogDisplaced((String) payload.get("catalogDisplaced"));
      journal.catalogWasPresent(
          !(payload.get("catalogWasPresent") instanceof Boolean present) || present);
      if (payload.get("activatedAt") instanceof String text) {
        journal.extra().put("activatedAt", text);
      }
      return journal;
    }
  }

  private static final class JournalPage {
    private final String collection;
    private final String publicId;
    private final String pageRef;
    private final String sourcePath;
    private String state;
    private final String stagedSha256;
    private final String legacySha256;
    private final String published;
    private final String staged;
    private final String displaced;

    private JournalPage(
        String collection,
        String publicId,
        String pageRef,
        String sourcePath,
        String state,
        String stagedSha256,
        String legacySha256,
        String published,
        String staged,
        String displaced) {
      this.collection = collection;
      this.publicId = publicId;
      this.pageRef = pageRef;
      this.sourcePath = sourcePath;
      this.state = state;
      this.stagedSha256 = stagedSha256;
      this.legacySha256 = legacySha256;
      this.published = published;
      this.staged = staged;
      this.displaced = displaced;
    }

    private String state() {
      return state;
    }

    private String collection() {
      return collection;
    }

    private String publicId() {
      return publicId;
    }

    private String pageRef() {
      return pageRef;
    }

    private String sourcePath() {
      return sourcePath;
    }

    private void state(String state) {
      this.state = state;
    }

    private String stagedSha256() {
      return stagedSha256;
    }

    private String legacySha256() {
      return legacySha256;
    }

    private String published() {
      return published;
    }

    private String staged() {
      return staged;
    }

    private String displaced() {
      return displaced;
    }

    private Map<String, Object> toPayload() {
      LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
      payload.put("collection", collection);
      payload.put("publicId", publicId);
      payload.put("pageRef", pageRef);
      payload.put("sourcePath", sourcePath);
      payload.put("state", state);
      payload.put("stagedSha256", stagedSha256);
      payload.put("legacySha256", legacySha256);
      payload.put("published", published);
      payload.put("staged", staged);
      payload.put("displaced", displaced);
      return payload;
    }

    private static JournalPage fromPayload(Map<String, Object> payload) {
      return new JournalPage(
          (String) payload.get("collection"),
          (String) payload.get("publicId"),
          (String) payload.get("pageRef"),
          (String) payload.get("sourcePath"),
          (String) payload.get("state"),
          (String) payload.get("stagedSha256"),
          (String) payload.get("legacySha256"),
          (String) payload.get("published"),
          (String) payload.get("staged"),
          (String) payload.get("displaced"));
    }
  }
}
