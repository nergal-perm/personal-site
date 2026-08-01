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
      writeReport(report, writeCanonical(inventory.toPayload(true)));
    }
    return inventory;
  }

  public DecisionSet validateDecisions(Inventory inventory, Path decisionsPath) {
    Objects.requireNonNull(inventory, "inventory");
    Objects.requireNonNull(decisionsPath, "decisionsPath");
    Map<String, Object> payload = readJson(decisionsPath);
    if (intValue(payload.get("schemaVersion")) != 1) {
      throw new DecisionValidationException("unsupported-schema", "decisions schemaVersion must be 1");
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
    Map<String, ReferenceMigrationAligner.MigrationOccurrence> occurrences = new LinkedHashMap<>();
    for (ReferenceMigrationAligner.MigrationPage page : inventory.pages()) {
      orderKeys.add(page.pageRef() + "/order");
      for (ReferenceMigrationAligner.MigrationOccurrence occurrence : page.occurrences()) {
        known.add(occurrence.occurrenceKey());
        occurrences.put(occurrence.occurrenceKey(), occurrence);
      }
    }
    List<Decision> validated = new ArrayList<>();
    for (Map.Entry<?, ?> entry : decisions.entrySet()) {
      String key = string(entry.getKey());
      if (!known.contains(key) && !orderKeys.contains(key)) {
        throw new DecisionValidationException("unknown-decision", "unknown decision key: " + key);
      }
      if (!(entry.getValue() instanceof Map<?, ?> decision)) {
        throw new DecisionValidationException("invalid-decision", "decision must be an object");
      }
      String decisionType = string(decision.get("decision"));
      if ("confirm".equals(decisionType)) {
        ReferenceMigrationAligner.Span span = validateConfirm(occurrences.get(key), decision);
        validated.add(new SpanConfirmDecision(key, span));
      } else if ("approve-corrected-order".equals(decisionType)) {
        validated.add(validateCorrectedOrder(inventory, key, decisionsPath, decision));
      } else {
        throw new DecisionValidationException("unsupported-decision", "unsupported decision: " + decisionType);
      }
    }
    return new DecisionSet(List.copyOf(validated));
  }

  private static ReferenceMigrationAligner.Span validateConfirm(
      ReferenceMigrationAligner.MigrationOccurrence occurrence,
      Map<?, ?> decision) {
    if (occurrence == null) {
      throw new DecisionValidationException("unsupported-decision", "confirm applies only to occurrences");
    }
    if (!(decision.get("enSpan") instanceof Map<?, ?> span)) {
      throw new DecisionValidationException("missing-en-span", "confirm requires enSpan");
    }
    int start = intValue(span.get("start"));
    int end = intValue(span.get("end"));
    ReferenceMigrationAligner.Span proposed = occurrence.proposedEnSpan();
    if (proposed == null || proposed.start() != start || proposed.end() != end) {
      throw new DecisionValidationException("hash-mismatch", "confirmed English span does not match inventory");
    }
    return new ReferenceMigrationAligner.Span(start, end);
  }

  private static PageCorrectedDecision validateCorrectedOrder(
      Inventory inventory,
      String key,
      Path decisionsPath,
      Map<?, ?> decision) {
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
    ReferenceMigrationAligner.MigrationPage page = inventory.pages().stream()
        .filter(candidate -> key.equals(candidate.pageRef() + "/order"))
        .findFirst()
        .orElseThrow(() -> new DecisionValidationException("unknown-decision", "unknown order decision"));
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
    return new PageCorrectedDecision(key, relative, approvedHash, expectedHash, bytes);
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

  private static void writeReport(Path report, byte[] bytes) {
    try {
      Path destination = report.toAbsolutePath().normalize();
      Path parent = destination.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path temporary = Files.createTempFile(parent, "." + destination.getFileName(), ".tmp");
      Files.write(temporary, bytes);
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException error) {
      throw new UncheckedIOException("cannot write inventory report", error);
    }
  }

  private static int intValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    throw new DecisionValidationException("invalid-decision", "expected integer");
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

    public Map<String, PageCorrectedDecision> correctedOrder() {
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
  }

  public sealed interface Decision permits SpanConfirmDecision, PageCorrectedDecision {
    String key();
  }

  public record SpanConfirmDecision(String key, ReferenceMigrationAligner.Span enSpan)
      implements Decision {
    public SpanConfirmDecision {
      key = requireText(key, "key");
      Objects.requireNonNull(enSpan, "enSpan");
    }
  }

  public record PageCorrectedDecision(
      String key,
      String correctedEnglishPath,
      String approvedEnglishSha256,
      String correctedEnglishSha256,
      byte[] correctedEnglishBytes) implements Decision {
    public PageCorrectedDecision {
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
