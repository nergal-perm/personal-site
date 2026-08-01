package dev.eugene.astroexport.migration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import dev.eugene.astroexport.references.VaultReferenceCatalog;
import dev.eugene.astroexport.references.VaultNoteDescriptor;
import dev.eugene.astroexport.references.VaultReferenceResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds deterministic read-only inventory JSON for legacy reference migration. */
public final class ReferenceMigrationInventory {
  public static final int SCHEMA_VERSION = 1;
  private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));

  public Inventory inspect(Path vault, Path review) {
    return inspect(vault, review, null);
  }

  public Inventory inspect(Path vault, Path review, Path report) {
    return inspect(vault, review, null, report);
  }

  public Inventory inspect(Path vault, Path review, Path astro, Path report) {
    Objects.requireNonNull(vault, "vault");
    Objects.requireNonNull(review, "review");
    if (report != null) {
      SemanticOutputSafety.preflight(report, review, "inventory report");
    }
    List<VaultNoteDescriptor> descriptors = VaultNoteDescriptor.scan(vault);
    VaultReferenceCatalog catalog = VaultReferenceCatalog.loadIfPresent(review)
        .reconcile(vault, descriptors);
    VaultReferenceResolver resolver = new VaultReferenceResolver(catalog);
    RouteScan currentRoutes = scanAstroRoutes(astro);
    Map<PublishedIdentity, SourceResolution> sourcePaths = sourcePaths(vault, descriptors);
    List<ApprovedSnapshot> snapshots = scanApproved(review);
    List<ReferenceMigrationAligner.MigrationPage> pages = new ArrayList<>();
    ReferenceMigrationAligner aligner = new ReferenceMigrationAligner();
    for (ApprovedSnapshot snapshot : snapshots) {
      SourceResolution source = snapshot.sourcePath() == null
          ? sourcePaths.getOrDefault(snapshot.identity(), SourceResolution.unresolved(snapshot.identity()))
          : SourceResolution.resolved(snapshot.sourcePath());
      String sourcePath = source.sourcePath();
      String pageRef = snapshot.pageRef();
      if (pageRef == null || pageRef.isBlank()) {
        pageRef = catalog.requireByCurrentPathOptional(sourcePath)
            .map(VaultReferenceCatalog.CatalogEntry::pageRef)
            .orElseGet(() -> provisionalPageRef(sourcePath));
      }
      RawInput rawInput = source.safe()
          ? readRawBody(vault, sourcePath)
          : RawInput.unsafe(source.reason());
      ReferenceMigrationAligner.RawPage rawPage = rawInput.safe()
          ? new ReferenceMigrationAligner.RawPage(
              pageRef,
              sourcePath,
              rawInput.body(),
              true,
              null,
              currentRoutes.routes(),
              currentRoutes.conflicts(),
              snapshot.legacyOrderPresent(),
              snapshot.legacyOrder())
          : new ReferenceMigrationAligner.RawPage(
              pageRef,
              sourcePath,
              "",
              false,
              rawInput.reason(),
              currentRoutes.routes(),
              currentRoutes.conflicts(),
              snapshot.legacyOrderPresent(),
              snapshot.legacyOrder());
      if (rawInput.safe() && currentRoutes.conflicts().containsKey(pageRef)) {
        rawPage = new ReferenceMigrationAligner.RawPage(
            pageRef,
            sourcePath,
            rawInput.body(),
            false,
            currentRoutes.conflicts().get(pageRef),
            currentRoutes.routes(),
            currentRoutes.conflicts(),
            snapshot.legacyOrderPresent(),
            snapshot.legacyOrder());
      }
      pages.add(aligner.align(
          rawPage,
          snapshot.russian(),
          snapshot.english(),
          resolver));
    }
    Inventory withoutHash = Inventory.unhashed(List.copyOf(pages));
    byte[] canonical = writeCanonical(withoutHash.toPayload(false));
    Inventory inventory = withoutHash.withHash(PageReferenceMapCodec.sha256(canonical));
    if (report != null) {
      writeReport(report, review, writeCanonical(inventory.toPayload(true)));
    }
    return inventory;
  }

  public DecisionSet validateDecisions(Inventory inventory, Path decisionsPath) {
    Objects.requireNonNull(inventory, "inventory");
    Objects.requireNonNull(decisionsPath, "decisionsPath");
    Map<String, Object> payload = readJson(decisionsPath);
    if (intValue(payload.get("schemaVersion"), "schemaVersion") != 1) {
      throw new DecisionValidationException("unsupported-schema", "decisions schemaVersion must be 1");
    }
    if (payload.containsKey("draftOnly") && !(payload.get("draftOnly") instanceof Boolean)) {
      throw new DecisionValidationException("invalid-decision", "draftOnly must be a boolean");
    }
    if (payload.containsKey("draftStatus") && !(payload.get("draftStatus") instanceof String)) {
      throw new DecisionValidationException("invalid-decision", "draftStatus must be a string");
    }
    if (payload.containsKey("draftOnly") || payload.containsKey("draftStatus")) {
      throw new DecisionValidationException(
          "draft-not-converted", "decision draft must be human-reviewed and converted before apply");
    }
    String inventorySha256 = string(payload.get("inventorySha256"));
    if (!inventory.inventorySha256().equals(inventorySha256)) {
      throw new DecisionValidationException("stale-inventory", "decision inventorySha256 does not match inventory");
    }
    Object rawDecisions = payload.get("decisions");
    if (!(rawDecisions instanceof Map<?, ?> decisions)) {
      throw new DecisionValidationException("missing-decisions", "decisions must be an object");
    }
    Set<String> known = new LinkedHashSet<>();
    Set<String> orderKeys = new LinkedHashSet<>();
    Set<String> pageKeys = new LinkedHashSet<>();
    Map<String, ReferenceMigrationAligner.MigrationOccurrence> occurrences = new LinkedHashMap<>();
    for (ReferenceMigrationAligner.MigrationPage page : inventory.pages()) {
      orderKeys.add(page.pageRef() + "/order");
      pageKeys.add(page.pageRef() + "/page");
      for (ReferenceMigrationAligner.MigrationOccurrence occurrence : page.occurrences()) {
        known.add(occurrence.occurrenceKey());
        occurrences.put(occurrence.occurrenceKey(), occurrence);
      }
    }
    List<Decision> validated = new ArrayList<>();
    Map<String, String> decisionTypes = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : decisions.entrySet()) {
      String key = string(entry.getKey());
      if (!known.contains(key) && !orderKeys.contains(key) && !pageKeys.contains(key)) {
        throw new DecisionValidationException("unknown-decision", "unknown decision key: " + key);
      }
      if (!(entry.getValue() instanceof Map<?, ?> decision)) {
        throw new DecisionValidationException("invalid-decision", "decision must be an object");
      }
      String decisionType = string(decision.get("decision"));
      decisionTypes.put(key, decisionType);
    }
    validateDecisionConflicts(inventory, decisionTypes, known, orderKeys, pageKeys);
    for (Map.Entry<?, ?> entry : decisions.entrySet()) {
      String key = string(entry.getKey());
      Map<?, ?> decision = (Map<?, ?>) entry.getValue();
      String decisionType = decisionTypes.get(key);
      if ("confirm".equals(decisionType)) {
        ReferenceMigrationAligner.Span span = validateConfirm(inventory, key, occurrences.get(key), decision);
        validated.add(new SpanConfirmDecision(key, span));
      } else if ("approve-corrected-page".equals(decisionType)) {
        validated.add(validateCorrectedPage(inventory, key, decisionsPath, decision));
      } else if ("approve-corrected-order".equals(decisionType)) {
        validated.add(validateCorrectedOrder(inventory, key, decisionsPath, decision));
      } else {
        throw new DecisionValidationException("unsupported-decision", "unsupported decision: " + decisionType);
      }
    }
    return new DecisionSet(List.copyOf(validated));
  }

  private static ReferenceMigrationAligner.Span validateConfirm(
      Inventory inventory,
      String key,
      ReferenceMigrationAligner.MigrationOccurrence occurrence,
      Map<?, ?> decision) {
    if (occurrence == null) {
      throw new DecisionValidationException("unsupported-decision", "confirm applies only to occurrences");
    }
    if (!(decision.get("enSpan") instanceof Map<?, ?> span)) {
      throw new DecisionValidationException("missing-en-span", "confirm requires enSpan");
    }
    int start = intValue(span.get("start"), "enSpan.start");
    int end = intValue(span.get("end"), "enSpan.end");
    ReferenceMigrationAligner.MigrationPage page = pageForOccurrence(inventory, key);
    if (occurrence.proposedEnSpan() == null
        || occurrence.proposedEnDestination() == null
        || page.status() != ReferenceMigrationAligner.PageStatus.CONFIRMED_NEEDED) {
      throw new DecisionValidationException(
          "ineligible-decision",
          "confirm requires a proposed span on a confirmed-needed page");
    }
    ReferenceMigrationAligner.Span proposed = occurrence.proposedEnSpan();
    if (proposed == null || proposed.start() != start || proposed.end() != end) {
      throw new DecisionValidationException("hash-mismatch", "confirmed English span does not match inventory");
    }
    return new ReferenceMigrationAligner.Span(start, end);
  }

  private static CorrectedOrderDecision validateCorrectedOrder(
      Inventory inventory,
      String key,
      Path decisionsPath,
      Map<?, ?> decision) {
    ReferenceMigrationAligner.MigrationPage page = inventory.pages().stream()
        .filter(candidate -> key.equals(candidate.pageRef() + "/order"))
        .findFirst()
        .orElseThrow(() -> new DecisionValidationException("unknown-decision", "unknown order decision"));
    if (page.status() != ReferenceMigrationAligner.PageStatus.ORDER_MISMATCH_PAGE) {
      throw new DecisionValidationException(
          "ineligible-decision", "corrected order applies only to order-mismatch pages");
    }
    String relative = string(decision.get("correctedEnglishPath"));
    Path corrected = resolveCorrected(decisionsPath, relative);
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(corrected);
      decode(bytes);
    } catch (CharacterCodingException error) {
      throw new DecisionValidationException("unsafe-input", "corrected English must be valid UTF-8");
    } catch (IOException error) {
      throw new DecisionValidationException("missing-corrected-english", "corrected English file is missing");
    }
    String expectedHash = string(decision.get("correctedEnglishSha256"));
    if (!PageReferenceMapCodec.sha256(bytes).equals(expectedHash)) {
      throw new DecisionValidationException("hash-mismatch", "corrected English hash does not match");
    }
    List<String> correctedOrder = englishLinkDestinations(new String(bytes, StandardCharsets.UTF_8));
    List<String> expectedOrder = page.occurrences().stream()
        .map(ReferenceMigrationInventory::englishDestination)
        .toList();
    if (expectedOrder.stream().anyMatch(Objects::isNull)) {
      throw new DecisionValidationException("incomplete-corrected-review", "inventory lacks complete English order");
    }
    if (!correctedOrder.equals(expectedOrder)) {
      throw new DecisionValidationException("order-mismatch", "corrected English order must match Russian order");
    }
    String approvedHash = string(decision.get("approvedEnglishSha256"));
    String inventoryApprovedHash = PageReferenceMapCodec.sha256(
        page.approvedEnglish().text().getBytes(StandardCharsets.UTF_8));
    if (!inventoryApprovedHash.equals(approvedHash)) {
      throw new DecisionValidationException(
          "hash-mismatch", "approved English hash does not match inventory");
    }
    validateApprovedSnapshotBinding(page.approvedEnglish(), approvedHash, "English");
    return new CorrectedOrderDecision(key, relative, approvedHash, expectedHash, bytes);
  }

  private static void validateDecisionConflicts(
      Inventory inventory,
      Map<String, String> decisionTypes,
      Set<String> known,
      Set<String> orderKeys,
      Set<String> pageKeys) {
    Map<String, List<String>> byPage = new LinkedHashMap<>();
    for (String key : decisionTypes.keySet()) {
      String pageRef = key;
      int slash = key.indexOf('/');
      if (known.contains(key)) {
        pageRef = key.substring(0, slash);
      } else if (orderKeys.contains(key) || pageKeys.contains(key)) {
        pageRef = key.substring(0, slash);
      }
      byPage.computeIfAbsent(pageRef, ignored -> new ArrayList<>()).add(key);
    }
    for (List<String> keys : byPage.values()) {
      boolean hasWholePage = keys.stream().anyMatch(key ->
          orderKeys.contains(key) || pageKeys.contains(key));
      if (hasWholePage && keys.size() > 1) {
        throw new DecisionValidationException(
            "conflicting-decision", "page decision conflicts with another decision: " + keys);
      }
    }
  }

  private static ReferenceMigrationAligner.MigrationPage pageForOccurrence(
      Inventory inventory, String key) {
    return inventory.pages().stream()
        .filter(page -> page.occurrences().stream()
            .anyMatch(occurrence -> key.equals(occurrence.occurrenceKey())))
        .findFirst()
        .orElseThrow(() -> new DecisionValidationException("unknown-decision", "unknown occurrence decision"));
  }

  private static PageCorrectedDecision validateCorrectedPage(
      Inventory inventory,
      String key,
      Path decisionsPath,
      Map<?, ?> decision) {
    ReferenceMigrationAligner.MigrationPage page = inventory.pages().stream()
        .filter(candidate -> key.equals(candidate.pageRef() + "/page"))
        .findFirst()
        .orElseThrow(() -> new DecisionValidationException("unknown-decision", "unknown page decision"));
    if (page.status() != ReferenceMigrationAligner.PageStatus.CONFIRMED_NEEDED
        || !page.approvedRussian().safe()
        || !page.approvedEnglish().safe()
        || page.occurrences().stream().anyMatch(occurrence ->
            occurrence.targetRef() == null || occurrence.targetRef().isBlank())) {
      throw new DecisionValidationException(
          "ineligible-decision",
          "corrected page applies only to safe confirmed-needed pages with resolved targets");
    }
    SnapshotBytes russian = readCorrectedSnapshot(
        decisionsPath,
        string(decision.get("correctedRussianPath")),
        string(decision.get("correctedRussianSha256")),
        "Russian");
    SnapshotBytes english = readCorrectedSnapshot(
        decisionsPath,
        string(decision.get("correctedEnglishPath")),
        string(decision.get("correctedEnglishSha256")),
        "English");
    validateApprovedSnapshotBinding(
        page.approvedRussian(),
        string(decision.get("approvedRussianSha256")),
        "Russian");
    validateApprovedSnapshotBinding(
        page.approvedEnglish(),
        string(decision.get("approvedEnglishSha256")),
        "English");
    validateCorrectedPageCoverage(page, russian.bytes(), english.bytes());
    return new PageCorrectedDecision(
        key,
        russian.path(),
        english.path(),
        PageReferenceMapCodec.sha256(page.approvedRussian().text().getBytes(StandardCharsets.UTF_8)),
        PageReferenceMapCodec.sha256(page.approvedEnglish().text().getBytes(StandardCharsets.UTF_8)),
        russian.hash(),
        english.hash(),
        russian.bytes(),
        english.bytes());
  }

  private static SnapshotBytes readCorrectedSnapshot(
      Path decisionsPath,
      String relative,
      String expectedHash,
      String language) {
    Path path = resolveCorrected(decisionsPath, relative);
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(path);
      decode(bytes);
    } catch (CharacterCodingException error) {
      throw new DecisionValidationException("unsafe-input", "corrected " + language + " must be valid UTF-8");
    } catch (IOException error) {
      throw new DecisionValidationException("missing-corrected-" + language.toLowerCase(),
          "corrected " + language + " snapshot is missing");
    }
    String actualHash = PageReferenceMapCodec.sha256(bytes);
    if (!actualHash.equals(expectedHash)) {
      throw new DecisionValidationException("hash-mismatch", "corrected " + language + " hash does not match");
    }
    return new SnapshotBytes(path.toString(), actualHash, bytes);
  }

  private static void validateApprovedSnapshotBinding(
      ReferenceMigrationAligner.ApprovedDocument approved,
      String expectedHash,
      String language) {
    String inventoryHash = PageReferenceMapCodec.sha256(
        approved.text().getBytes(StandardCharsets.UTF_8));
    if (!inventoryHash.equals(expectedHash)) {
      throw new DecisionValidationException(
          "hash-mismatch", "approved " + language + " hash does not match inventory");
    }
    try {
      byte[] current = Files.readAllBytes(Path.of(approved.path()));
      decode(current);
      if (!PageReferenceMapCodec.sha256(current).equals(expectedHash)) {
        throw new DecisionValidationException(
            "hash-mismatch", "approved " + language + " snapshot changed after inventory");
      }
    } catch (CharacterCodingException error) {
      throw new DecisionValidationException("unsafe-input", "approved " + language + " must be valid UTF-8");
    } catch (IOException error) {
      throw new DecisionValidationException("missing-approved-" + language.toLowerCase(),
          "approved " + language + " snapshot is missing");
    }
  }

  private static void validateCorrectedPageCoverage(
      ReferenceMigrationAligner.MigrationPage page,
      byte[] russian,
      byte[] english) {
    List<String> expected = page.occurrences().stream()
        .map(ReferenceMigrationInventory::referenceId)
        .toList();
    List<String> russianIds = semanticReferenceIds(new String(russian, StandardCharsets.UTF_8));
    List<String> englishIds = semanticReferenceIds(new String(english, StandardCharsets.UTF_8));
    if (!russianIds.equals(expected) || !englishIds.equals(expected)) {
      throw new DecisionValidationException(
          "incomplete-corrected-page", "corrected Russian and English snapshots must cover occurrences in order");
    }
  }

  private static List<String> semanticReferenceIds(String markdown) {
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
        "\\]\\(ref:([^#)\\r\\n]+)(?:#[^)]*)?\\)").matcher(markdown);
    List<String> ids = new ArrayList<>();
    while (matcher.find()) {
      ids.add(matcher.group(1));
    }
    return List.copyOf(ids);
  }

  private static String referenceId(ReferenceMigrationAligner.MigrationOccurrence occurrence) {
    if (occurrence.proposedReferenceId() != null && !occurrence.proposedReferenceId().isBlank()) {
      return occurrence.proposedReferenceId();
    }
    return "ref-%04d".formatted(occurrence.sourceOrdinal());
  }

  private static String englishDestination(ReferenceMigrationAligner.MigrationOccurrence occurrence) {
    return occurrence.proposedEnDestination();
  }

  private static List<String> englishLinkDestinations(String markdown) {
    List<String> destinations = new ArrayList<>();
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
        "\\[[^\\]\\n]*?\\]\\(([^\\r\\n)]*?)\\)").matcher(markdown);
    while (matcher.find()) {
      String destination = matcher.group(1);
      if (destination.startsWith("/en/")) {
        destinations.add(destination);
      }
    }
    return List.copyOf(destinations);
  }

  private static Path resolveCorrected(Path decisionsPath, String relative) {
    Path candidate = Path.of(relative);
    if (candidate.isAbsolute()) {
      throw new DecisionValidationException("escaping-corrected-path", "correctedEnglishPath must be relative");
    }
    Path base = decisionsPath.toAbsolutePath().normalize().getParent();
    Path resolved = base.resolve(candidate).normalize();
    if (!resolved.startsWith(base)) {
      throw new DecisionValidationException("escaping-corrected-path", "correctedEnglishPath escapes decision directory");
    }
    return resolved;
  }

  private static List<ApprovedSnapshot> scanApproved(Path review) {
    Path root = review.toAbsolutePath().normalize();
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    List<ApprovedSnapshot> snapshots = new ArrayList<>();
    try (var collections = Files.list(root)) {
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
            if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
              snapshots.add(readSnapshot(published, new PublishedIdentity(
                  collection.getFileName().toString(), page.getFileName().toString())));
            }
          }
        }
      }
    } catch (IOException error) {
      throw new UncheckedIOException("cannot scan review workspace", error);
    }
    return List.copyOf(snapshots);
  }

  private static ApprovedSnapshot readSnapshot(Path published, PublishedIdentity identity) {
    Path referencesPath = published.resolve("references.json");
    boolean hasReferences = Files.exists(referencesPath, LinkOption.NOFOLLOW_LINKS);
    SafeLeaf references = hasReferences ? readSafeLeaf(referencesPath) : null;
    String pageRef = null;
    String sourcePath = null;
    if (hasReferences && references.safe()) {
      try {
        PageReferenceMap map = PageReferenceMapCodec.read(references.bytes(), published.resolve("references.json").toString());
        pageRef = map.pageRef();
        sourcePath = map.sourcePath();
        List<String> order = map.order();
        SafeLeaf ru = readSafeLeaf(published.resolve("ru.md"));
        SafeLeaf en = readSafeLeaf(published.resolve("en.md"));
        String unsafe = firstUnsafe(ru, en, references);
        if (unsafe != null) {
          return unsafeSnapshot(published, identity, pageRef, sourcePath, unsafe, order);
        }
        return new ApprovedSnapshot(
            identity,
            pageRef,
            sourcePath,
            true,
            order,
            ReferenceMigrationAligner.ApprovedDocument.valid(published.resolve("ru.md").toString(), ru.bytes()),
            ReferenceMigrationAligner.ApprovedDocument.valid(published.resolve("en.md").toString(), en.bytes()));
      } catch (RuntimeException error) {
        references = SafeLeaf.unsafe("unsafe approved snapshot references.json: " + error.getMessage());
      }
    }
    SafeLeaf ru = readSafeLeaf(published.resolve("ru.md"));
    SafeLeaf en = readSafeLeaf(published.resolve("en.md"));
    String unsafe = hasReferences ? firstUnsafe(ru, en, references) : firstUnsafe(ru, en);
    if (unsafe != null) {
      return unsafeSnapshot(published, identity, pageRef, sourcePath, unsafe, List.of());
    }
    return new ApprovedSnapshot(
        identity,
        pageRef,
        sourcePath,
        false,
        List.of(),
        ReferenceMigrationAligner.ApprovedDocument.valid(published.resolve("ru.md").toString(), ru.bytes()),
        ReferenceMigrationAligner.ApprovedDocument.valid(published.resolve("en.md").toString(), en.bytes()));
  }

  private static ApprovedSnapshot unsafeSnapshot(
      Path published,
      PublishedIdentity identity,
      String pageRef,
      String sourcePath,
      String unsafe,
      List<String> order) {
    return new ApprovedSnapshot(
        identity,
        pageRef,
        sourcePath,
        true,
        order,
        ReferenceMigrationAligner.ApprovedDocument.unsafe(published.resolve("ru.md").toString(), unsafe),
        ReferenceMigrationAligner.ApprovedDocument.unsafe(published.resolve("en.md").toString(), unsafe));
  }

  private static String firstUnsafe(SafeLeaf... leaves) {
    for (SafeLeaf leaf : leaves) {
      if (!leaf.safe()) {
        return leaf.reason();
      }
    }
    return null;
  }

  private static SafeLeaf readSafeLeaf(Path path) {
    try {
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
        return SafeLeaf.unsafe("unsafe approved snapshot leaf " + path + ": missing or symbolic");
      }
      BasicFileAttributes attributes = Files.readAttributes(
          path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile()) {
        return SafeLeaf.unsafe("unsafe approved snapshot leaf " + path + ": must be a regular file");
      }
      try {
        Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        if (links instanceof Number count && count.longValue() != 1) {
          return SafeLeaf.unsafe("unsafe approved snapshot leaf " + path + ": must have exactly one hard link");
        }
      } catch (UnsupportedOperationException ignored) {
        // Keep checks portable on non-Unix filesystems.
      }
      byte[] bytes = Files.readAllBytes(path);
      decode(bytes);
      return SafeLeaf.safe(bytes);
    } catch (IOException | RuntimeException error) {
      return SafeLeaf.unsafe("unsafe approved snapshot leaf " + path + ": " + error.getMessage());
    }
  }

  private static RawInput readRawBody(Path vault, String sourcePath) {
    Path path = bounded(vault, sourcePath);
    try {
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
        return RawInput.unsafe("current source is missing or symbolic: " + sourcePath);
      }
      String markdown = Files.readString(path, StandardCharsets.UTF_8);
      String body = FrontmatterDocument.parse(path, sourcePath, markdown).body();
      return RawInput.safe(body);
    } catch (IOException error) {
      return RawInput.unsafe("current source cannot be read: " + sourcePath);
    }
  }

  private static Map<PublishedIdentity, SourceResolution> sourcePaths(
      Path vault,
      List<VaultNoteDescriptor> descriptors) {
    Map<PublishedIdentity, List<String>> candidates = new LinkedHashMap<>();
    for (VaultNoteDescriptor descriptor : descriptors) {
      Path source = bounded(vault, descriptor.vaultPath());
      try {
        FrontmatterDocument document = FrontmatterDocument.parse(
            source,
            descriptor.vaultPath(),
            Files.readString(source, StandardCharsets.UTF_8));
        String collection = metadataText(document.metadata().get("publicCollection"));
        String publicId = metadataText(document.metadata().get("publicId"));
        if (collection != null && publicId != null) {
          candidates.computeIfAbsent(new PublishedIdentity(collection, publicId), ignored -> new ArrayList<>())
              .add(descriptor.vaultPath());
        }
      } catch (IOException | RuntimeException ignored) {
        // Invalid or unreadable sources remain unavailable to legacy-pair resolution.
      }
    }
    Map<PublishedIdentity, SourceResolution> resolved = new LinkedHashMap<>();
    for (Map.Entry<PublishedIdentity, List<String>> entry : candidates.entrySet()) {
      List<String> paths = entry.getValue();
      if (paths.size() == 1) {
        resolved.put(entry.getKey(), SourceResolution.resolved(paths.getFirst()));
      } else {
        resolved.put(entry.getKey(), SourceResolution.ambiguous(entry.getKey(), paths));
      }
    }
    return Map.copyOf(resolved);
  }

  private static String metadataText(Object value) {
    if (!(value instanceof String text)) {
      return null;
    }
    String normalized = text.strip();
    return normalized.isBlank() ? null : normalized;
  }

  private static RouteScan scanAstroRoutes(Path astro) {
    if (astro == null || !Files.isDirectory(astro, LinkOption.NOFOLLOW_LINKS)) {
      return new RouteScan(Map.of(), Map.of());
    }
    Map<String, RouteBuilder> routes = new LinkedHashMap<>();
    Map<String, String> conflicts = new LinkedHashMap<>();
    try (var paths = Files.walk(astro)) {
      for (Path path : paths
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".md"))
          .sorted()
          .toList()) {
        FrontmatterDocument document = FrontmatterDocument.parse(
            path,
            astro.relativize(path).toString(),
            Files.readString(path, StandardCharsets.UTF_8));
        String pageRef = document.metadata().get("pageRef") instanceof String value ? value : null;
        String route = document.metadata().get("route") instanceof String value ? value : null;
        if (pageRef == null || route == null) {
          continue;
        }
        RouteBuilder builder = routes.computeIfAbsent(pageRef, ignored -> new RouteBuilder());
        if (route.startsWith("/ru/")) {
          builder.add("ru", route, conflicts, pageRef);
        } else if (route.startsWith("/en/")) {
          builder.add("en", route, conflicts, pageRef);
        }
      }
    } catch (IOException error) {
      return new RouteScan(Map.of(), Map.of("astro", "conflicting Astro routes: cannot scan Astro routes"));
    }
    LinkedHashMap<String, ReferenceMigrationAligner.RoutePair> result = new LinkedHashMap<>();
    for (Map.Entry<String, RouteBuilder> entry : routes.entrySet()) {
      result.put(entry.getKey(), new ReferenceMigrationAligner.RoutePair(entry.getValue().ru, entry.getValue().en));
    }
    return new RouteScan(Map.copyOf(result), Map.copyOf(conflicts));
  }

  private static Path bounded(Path root, String relative) {
    Path base = root.toAbsolutePath().normalize();
    Path resolved = base.resolve(relative).normalize();
    if (!resolved.startsWith(base)) {
      throw new IllegalArgumentException("path escapes root: " + relative);
    }
    return resolved;
  }

  private static String provisionalPageRef(String sourcePath) {
    return "vault-ref-" + PageReferenceMapCodec.sha256(
        ("reference-migration-inventory:v1:" + sourcePath).getBytes(StandardCharsets.UTF_8))
        .substring(0, 16);
  }

  private static void decode(byte[] bytes) throws CharacterCodingException {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes));
  }

  private static Map<String, Object> readJson(Path path) {
    try {
      return JSON.readValue(Files.readAllBytes(path), new TypeReference<LinkedHashMap<String, Object>>() { });
    } catch (StreamReadException error) {
      throw new DecisionValidationException("duplicate-decision", error.getOriginalMessage());
    } catch (IOException error) {
      throw new DecisionValidationException("missing-decisions", "cannot read decisions file");
    }
  }

  private static byte[] writeCanonical(Map<String, Object> payload) {
    try {
      return JSON.writeValueAsBytes(payload);
    } catch (IOException error) {
      throw new IllegalStateException("cannot write inventory JSON", error);
    }
  }

  private static void writeReport(Path report, Path review, byte[] bytes) {
    try {
      Path destination = SemanticOutputSafety.preflight(report, review, "inventory report");
      Path parent = destination.getParent();
      if (parent != null) {
        SemanticOutputSafety.createDirectories(parent, review, "inventory report");
      }
      Path temporary = Files.createTempFile(parent, "." + destination.getFileName(), ".tmp");
      SemanticOutputSafety.preflight(temporary, review, "inventory report temporary");
      Files.write(temporary, bytes);
      SemanticOutputSafety.preflight(destination, review, "inventory report");
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException error) {
      throw new UncheckedIOException("cannot write inventory report", error);
    }
  }

  private static int intValue(Object value, String field) {
    if (value instanceof Integer valueAsInt) {
      return valueAsInt;
    }
    if (value instanceof Long valueAsLong) {
      if (valueAsLong < Integer.MIN_VALUE || valueAsLong > Integer.MAX_VALUE) {
        throw new DecisionValidationException("invalid-decision", field + " must be within 32-bit integer range");
      }
      return valueAsLong.intValue();
    }
    if (value instanceof BigInteger valueAsBigInteger) {
      if (valueAsBigInteger.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
          || valueAsBigInteger.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
        throw new DecisionValidationException("invalid-decision", field + " must be within 32-bit integer range");
      }
      return valueAsBigInteger.intValue();
    }
    if (value instanceof Short valueAsShort) {
      return valueAsShort.intValue();
    }
    if (value instanceof Byte valueAsByte) {
      return valueAsByte.intValue();
    }
    throw new DecisionValidationException("invalid-decision", field + " must be an integer");
  }

  private static String string(Object value) {
    if (value instanceof String text && !text.isBlank()) {
      return text;
    }
    throw new DecisionValidationException("invalid-decision", "expected non-blank string");
  }

  private record ApprovedSnapshot(
      PublishedIdentity identity,
      String pageRef,
      String sourcePath,
      boolean legacyOrderPresent,
      List<String> legacyOrder,
      ReferenceMigrationAligner.ApprovedDocument russian,
      ReferenceMigrationAligner.ApprovedDocument english) {
    private ApprovedSnapshot {
      legacyOrder = legacyOrder == null ? List.of() : List.copyOf(legacyOrder);
    }
  }

  private record RawInput(boolean safe, String body, String reason) {
    static RawInput safe(String body) {
      return new RawInput(true, body, null);
    }

    static RawInput unsafe(String reason) {
      return new RawInput(false, "", reason);
    }
  }

  private record PublishedIdentity(String collection, String publicId) {
    String display() {
      return collection + "/" + publicId;
    }
  }

  private record SourceResolution(boolean safe, String sourcePath, String reason) {
    static SourceResolution resolved(String sourcePath) {
      return new SourceResolution(true, sourcePath, null);
    }

    static SourceResolution unresolved(PublishedIdentity identity) {
      return new SourceResolution(false, "unresolved-source:" + identity.display(),
          "no unique current vault source declares " + identity.display());
    }

    static SourceResolution ambiguous(PublishedIdentity identity, List<String> paths) {
      return new SourceResolution(false, "ambiguous-source:" + identity.display(),
          "multiple current vault sources declare " + identity.display() + ": " + String.join(", ", paths));
    }
  }

  private record RouteScan(
      Map<String, ReferenceMigrationAligner.RoutePair> routes,
      Map<String, String> conflicts) {
  }

  private static final class RouteBuilder {
    private String ru;
    private String en;

    private void add(
        String language,
        String route,
        Map<String, String> conflicts,
        String pageRef) {
      String current = "ru".equals(language) ? ru : en;
      if (current != null && !current.equals(route)) {
        conflicts.put(pageRef, "conflicting Astro routes for " + pageRef + " " + language);
        return;
      }
      if ("ru".equals(language)) {
        ru = route;
      } else {
        en = route;
      }
    }
  }

  private record SafeLeaf(boolean safe, byte[] bytes, String reason) {
    static SafeLeaf safe(byte[] bytes) {
      return new SafeLeaf(true, bytes.clone(), null);
    }

    static SafeLeaf unsafe(String reason) {
      return new SafeLeaf(false, null, reason);
    }

    @Override
    public byte[] bytes() {
      return bytes == null ? null : bytes.clone();
    }
  }

  public record Inventory(String inventorySha256, List<ReferenceMigrationAligner.MigrationPage> pages) {
    public Inventory {
      pages = List.copyOf(pages);
    }

    static Inventory unhashed(List<ReferenceMigrationAligner.MigrationPage> pages) {
      return new Inventory(null, pages);
    }

    Inventory withHash(String hash) {
      return new Inventory(hash, pages);
    }

    public Summary summary() {
      int exact = 0;
      int confirmedNeeded = 0;
      int unresolved = 0;
      int orderMismatch = 0;
      int unsafe = 0;
      int occurrences = 0;
      for (ReferenceMigrationAligner.MigrationPage page : pages) {
        occurrences += page.occurrences().size();
        switch (page.status()) {
          case EXACT_PAGE -> exact++;
          case CONFIRMED_NEEDED -> confirmedNeeded++;
          case UNRESOLVED_PAGE -> unresolved++;
          case ORDER_MISMATCH_PAGE -> orderMismatch++;
          case UNSAFE_PAGE -> unsafe++;
        }
      }
      return new Summary(exact, confirmedNeeded, unresolved, orderMismatch, unsafe, occurrences);
    }

    Map<String, Object> toPayload(boolean includeHash) {
      LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
      payload.put("schemaVersion", SCHEMA_VERSION);
      if (includeHash) {
        payload.put("inventorySha256", inventorySha256);
      }
      payload.put("summary", summary().toPayload());
      payload.put("pages", pages.stream().map(Inventory::pagePayload).toList());
      return payload;
    }

    private static Map<String, Object> pagePayload(ReferenceMigrationAligner.MigrationPage page) {
      LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
      payload.put("pageRef", page.pageRef());
      payload.put("sourcePath", page.sourcePath());
      payload.put("status", page.status().json());
      payload.put("automatic", page.automatic());
      payload.put("occurrences", page.occurrences().stream().map(Inventory::occurrencePayload).toList());
      return payload;
    }

    private static Map<String, Object> occurrencePayload(
        ReferenceMigrationAligner.MigrationOccurrence occurrence) {
      LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
      payload.put("occurrenceKey", occurrence.occurrenceKey());
      payload.put("classification", occurrence.classification().json());
      payload.put("rawWikilink", occurrence.rawWikilink());
      payload.put("sourceContext", occurrence.sourceContext());
      payload.put("ruContext", occurrence.ruContext());
      payload.put("proposedEnContext", occurrence.proposedEnContext());
      payload.put("proposedEnSpan", spanPayload(occurrence.proposedEnSpan()));
      payload.put("sourceOrdinal", occurrence.sourceOrdinal());
      payload.put("targetRef", occurrence.targetRef());
      payload.put("heading", occurrence.heading());
      payload.put("reason", occurrence.reason());
      payload.put("proposedReference", proposedReferencePayload(
          occurrence.proposedReferenceId(),
          occurrence.proposedReference()));
      return payload;
    }

    private static Map<String, Object> spanPayload(ReferenceMigrationAligner.Span span) {
      if (span == null) {
        return null;
      }
      LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
      payload.put("start", span.start());
      payload.put("end", span.end());
      return payload;
    }

    private static Map<String, Object> proposedReferencePayload(
        String id,
        PageReferenceMap.Reference reference) {
      if (id == null || reference == null) {
        return null;
      }
      LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", id);
      payload.put("targetRef", reference.targetRef());
      payload.put("authoredTarget", reference.authoredTarget());
      payload.put("heading", reference.heading());
      payload.put("label", reference.label());
      return payload;
    }
  }

  public record Summary(
      int exact,
      int confirmedNeeded,
      int unresolved,
      int orderMismatch,
      int unsafe,
      int occurrences) {
    public Map<String, Integer> toPayload() {
      LinkedHashMap<String, Integer> payload = new LinkedHashMap<>();
      payload.put("exact", exact);
      payload.put("confirmedNeeded", confirmedNeeded);
      payload.put("unresolved", unresolved);
      payload.put("orderMismatch", orderMismatch);
      payload.put("unsafe", unsafe);
      payload.put("occurrences", occurrences);
      return payload;
    }

    public boolean decisionsRequired() {
      return confirmedNeeded > 0 || unresolved > 0 || orderMismatch > 0 || unsafe > 0;
    }
  }

  public record DecisionSet(List<Decision> decisions) {
    public DecisionSet {
      decisions = List.copyOf(decisions);
    }

    public List<String> keys() {
      return decisions.stream().map(Decision::key).toList();
    }

    public Map<String, CorrectedOrderDecision> correctedOrder() {
      LinkedHashMap<String, CorrectedOrderDecision> corrected = decisions.stream()
          .filter(CorrectedOrderDecision.class::isInstance)
          .map(CorrectedOrderDecision.class::cast)
          .collect(java.util.stream.Collectors.toMap(
              CorrectedOrderDecision::key,
              decision -> decision,
              (first, second) -> first,
              LinkedHashMap::new));
      return Map.copyOf(corrected);
    }

    public Map<String, PageCorrectedDecision> correctedPages() {
      LinkedHashMap<String, PageCorrectedDecision> corrected = decisions.stream()
          .filter(PageCorrectedDecision.class::isInstance)
          .map(PageCorrectedDecision.class::cast)
          .collect(java.util.stream.Collectors.toMap(
              PageCorrectedDecision::key,
              decision -> decision,
              (first, second) -> first,
              LinkedHashMap::new));
      return Map.copyOf(corrected);
    }

    /** Returns the validated decisions in the shape consumed by the apply planner. */
    public ExecutableDecisions executable() {
      return new ExecutableDecisions(correctedOrder(), correctedPages());
    }
  }

  public record ExecutableDecisions(
      Map<String, CorrectedOrderDecision> correctedOrder,
      Map<String, PageCorrectedDecision> correctedPages) {
    public ExecutableDecisions {
      correctedOrder = Map.copyOf(correctedOrder);
      correctedPages = Map.copyOf(correctedPages);
    }
  }

  public sealed interface Decision permits SpanConfirmDecision, CorrectedOrderDecision, PageCorrectedDecision {
    String key();
  }

  public record SpanConfirmDecision(String key, ReferenceMigrationAligner.Span enSpan)
      implements Decision {
    public SpanConfirmDecision {
      key = requireText(key, "key");
      Objects.requireNonNull(enSpan, "enSpan");
    }
  }

  public record CorrectedOrderDecision(
      String key,
      String correctedEnglishPath,
      String approvedEnglishSha256,
      String correctedEnglishSha256,
      byte[] correctedEnglishBytes) implements Decision {
    public CorrectedOrderDecision {
      key = requireText(key, "key");
      correctedEnglishPath = requireText(correctedEnglishPath, "correctedEnglishPath");
      approvedEnglishSha256 = requireText(approvedEnglishSha256, "approvedEnglishSha256");
      correctedEnglishSha256 = requireText(correctedEnglishSha256, "correctedEnglishSha256");
      correctedEnglishBytes = correctedEnglishBytes.clone();
    }

    @Override
    public byte[] correctedEnglishBytes() {
      return correctedEnglishBytes.clone();
    }
  }

  public record PageCorrectedDecision(
      String key,
      String correctedRussianPath,
      String correctedEnglishPath,
      String approvedRussianSha256,
      String approvedEnglishSha256,
      String correctedRussianSha256,
      String correctedEnglishSha256,
      byte[] correctedRussianBytes,
      byte[] correctedEnglishBytes) implements Decision {
    public PageCorrectedDecision {
      key = requireText(key, "key");
      correctedRussianPath = requireText(correctedRussianPath, "correctedRussianPath");
      correctedEnglishPath = requireText(correctedEnglishPath, "correctedEnglishPath");
      approvedRussianSha256 = requireText(approvedRussianSha256, "approvedRussianSha256");
      approvedEnglishSha256 = requireText(approvedEnglishSha256, "approvedEnglishSha256");
      correctedRussianSha256 = requireText(correctedRussianSha256, "correctedRussianSha256");
      correctedEnglishSha256 = requireText(correctedEnglishSha256, "correctedEnglishSha256");
      correctedRussianBytes = correctedRussianBytes.clone();
      correctedEnglishBytes = correctedEnglishBytes.clone();
    }

    @Override
    public byte[] correctedRussianBytes() {
      return correctedRussianBytes.clone();
    }

    @Override
    public byte[] correctedEnglishBytes() {
      return correctedEnglishBytes.clone();
    }
  }

  private record SnapshotBytes(String path, String hash, byte[] bytes) {
    private SnapshotBytes {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  public static final class DecisionValidationException extends RuntimeException {
    private final String code;

    public DecisionValidationException(String code, String message) {
      super(message);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
