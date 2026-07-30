package dev.eugene.astroexport.migration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.fs.AtomicExchange;
import dev.eugene.astroexport.fs.JnaAtomicExchange;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import dev.eugene.astroexport.references.VaultReferenceCatalog;
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
    Path catalog = VaultReferenceCatalog.catalogPath(request.review());
    byte[] catalogBytes = readSafe(catalog);
    String catalogSha = PageReferenceMapCodec.sha256(catalogBytes);
    Path root = request.review().resolve(".semantic-links");
    Path stagingRoot = root.resolve("staging-v1");
    Path recoveryRoot = root.resolve("recovery-v1");
    recreateDirectory(stagingRoot);
    Files.createDirectories(recoveryRoot);
    forceDirectory(root);

    Journal journal = new Journal(
        "planned",
        inventory.inventorySha256(),
        catalogSha,
        ".semantic-links/recovery-v1",
        new ArrayList<>());
    writeJournal(request.review(), journal);
    hooks.after(Boundary.CATALOG_STAGED, 1);
    List<PagePlan> pages = new ArrayList<>();
    int index = 0;
    for (ReferenceMigrationAligner.MigrationPage page : inventory.pages()) {
      index++;
      PageIdentity identity = findPage(request.review(), page.sourcePath());
      Path pageStage = stagingRoot.resolve(identity.collection()).resolve(identity.publicId()).resolve("published");
      Files.createDirectories(pageStage);
      byte[] ru = semanticBytes(page.approvedRussian().text(), page, true);
      byte[] en = semanticBytes(page.approvedEnglish().text(), page, false);
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
          request.review().relativize(published).toString(),
          request.review().relativize(pageStage).toString(),
          request.review().relativize(displaced).toString());
      journal.pages().add(journalPage);
      writeJournal(request.review(), journal);
      hooks.after(Boundary.PAGE_STAGED, index);
      pages.add(new PagePlan(published, pageStage, displaced, journalPage));
    }
    hooks.after(Boundary.PARITY_PROJECTED, 1);
    hooks.after(Boundary.ASTRO_GATED, 1);
    return new ApplyPlan(request.review(), catalogBytes, journal, pages);
  }

  private void install(ApplyPlan plan, MigrationHooks hooks) throws IOException {
    int index = 0;
    for (PagePlan page : plan.pages()) {
      index++;
      Files.createDirectories(page.displaced().getParent());
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
    plan.journal().state("cleanup-pending");
    writeJournal(plan.review(), plan.journal());
    writeActivationMarker(plan.review(), plan.journal());
    hooks.after(Boundary.MARKER_WRITE, 1);
    plan.journal().state("complete");
    for (JournalPage page : plan.journal().pages()) {
      page.state("complete");
    }
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
      } else if ("installed".equals(page.state()) || "verified".equals(page.state())) {
        if (!stagedHashMatches(published, page.stagedSha256())) {
          throw new IllegalStateException("installed recovery hash mismatch: " + published);
        }
      }
      page.state("complete");
    }
    journal.state("complete");
    writeActivationMarker(review, journal);
    writeJournal(review, journal);
  }

  private void rollback(Path review, Journal journal) throws IOException {
    List<JournalPage> pages = new ArrayList<>(journal.pages());
    pages.sort(Comparator.comparing(JournalPage::published).reversed());
    for (JournalPage page : pages) {
      Path published = review.resolve(page.published());
      Path displaced = review.resolve(page.displaced());
      if (Files.exists(displaced, LinkOption.NOFOLLOW_LINKS)
          && Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
        atomicExchange.exchange(published, displaced);
        forceDirectory(published.getParent());
      } else if (Files.exists(displaced, LinkOption.NOFOLLOW_LINKS)) {
        Files.createDirectories(published.getParent());
        Files.move(displaced, published, StandardCopyOption.ATOMIC_MOVE);
      } else if (Files.exists(review.resolve(page.staged()), LinkOption.NOFOLLOW_LINKS)
          && Files.exists(published, LinkOption.NOFOLLOW_LINKS)
          && ("installed".equals(page.state()) || "verified".equals(page.state()))) {
        atomicExchange.exchange(published, review.resolve(page.staged()));
        forceDirectory(published.getParent());
      } else if (Files.exists(review.resolve(page.staged()), LinkOption.NOFOLLOW_LINKS)) {
        deleteTree(review.resolve(page.staged()));
      }
    }
    Files.deleteIfExists(SemanticSchemaState.activationMarker(review));
    Path journalPath = SemanticSchemaState.migrationJournal(review);
    Path rollbackEvidence = journalPath.resolveSibling("migration-v1.rollback-complete.json");
    writeJson(rollbackEvidence, journal.toPayload());
    Files.deleteIfExists(journalPath);
    forceDirectory(journalPath.getParent());
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
          "ref:" + occurrence.proposedReferenceId()
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

  private static PageReferenceMap references(
      ReferenceMigrationAligner.MigrationPage page,
      byte[] ru,
      byte[] en) {
    LinkedHashMap<String, PageReferenceMap.Reference> references = new LinkedHashMap<>();
    List<String> order = page.occurrences().stream()
        .map(ReferenceMigrationAligner.MigrationOccurrence::proposedReferenceId)
        .toList();
    for (ReferenceMigrationAligner.MigrationOccurrence occurrence : page.occurrences()) {
      references.put(occurrence.proposedReferenceId(), occurrence.proposedReference());
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

  private static PageIdentity findPage(Path review, String sourcePath) throws IOException {
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
    return new MigrationIncompleteException(
        "semantic migration incomplete; journal="
            + SemanticSchemaState.migrationJournal(review)
            + " recovery="
            + review.resolve(".semantic-links/recovery-v1"),
        error);
  }

  public record ApplyRequest(Path vault, Path review, Path astro, Path report, Path decisions) { }

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
      Path review,
      byte[] catalog,
      Journal journal,
      List<PagePlan> pages) { }

  private record PagePlan(
      Path published,
      Path staged,
      Path displaced,
      JournalPage journalPage) { }

  private record PageIdentity(String collection, String publicId) { }

  private static final class Journal {
    private String state;
    private final String inventorySha256;
    private final String catalogSha256;
    private final String recoveryRoot;
    private final List<JournalPage> pages;
    private final Map<String, Object> extra = new LinkedHashMap<>();

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

    private Map<String, Object> toPayload() {
      LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
      payload.put("schemaVersion", 1);
      payload.put("state", state);
      payload.put("inventorySha256", inventorySha256);
      payload.put("catalogSha256", catalogSha256);
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
        String published,
        String staged,
        String displaced) {
      this.collection = collection;
      this.publicId = publicId;
      this.pageRef = pageRef;
      this.sourcePath = sourcePath;
      this.state = state;
      this.stagedSha256 = stagedSha256;
      this.published = published;
      this.staged = staged;
      this.displaced = displaced;
    }

    private String state() {
      return state;
    }

    private void state(String state) {
      this.state = state;
    }

    private String stagedSha256() {
      return stagedSha256;
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
          (String) payload.get("published"),
          (String) payload.get("staged"),
          (String) payload.get("displaced"));
    }
  }
}
