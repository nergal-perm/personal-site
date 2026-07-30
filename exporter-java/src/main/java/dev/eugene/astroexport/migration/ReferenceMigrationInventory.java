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
    VaultReferenceCatalog catalog = VaultReferenceCatalog.loadIfPresent(review);
    VaultReferenceResolver resolver = new VaultReferenceResolver(catalog);
    Map<String, ReferenceMigrationAligner.RoutePair> currentRoutes = scanAstroRoutes(astro);
    List<ApprovedSnapshot> snapshots = scanApproved(review);
    List<ReferenceMigrationAligner.MigrationPage> pages = new ArrayList<>();
    ReferenceMigrationAligner aligner = new ReferenceMigrationAligner();
    for (ApprovedSnapshot snapshot : snapshots) {
      String pageRef = snapshot.pageRef();
      if (pageRef == null || pageRef.isBlank()) {
        pageRef = provisionalPageRef(snapshot.sourcePath());
      }
      RawInput rawInput = readRawBody(vault, snapshot.sourcePath());
      ReferenceMigrationAligner.RawPage rawPage = rawInput.safe()
          ? new ReferenceMigrationAligner.RawPage(
              pageRef,
              snapshot.sourcePath(),
              rawInput.body(),
              true,
              null,
              currentRoutes,
              snapshot.legacyOrder())
          : new ReferenceMigrationAligner.RawPage(
              pageRef,
              snapshot.sourcePath(),
              "",
              false,
              rawInput.reason(),
              currentRoutes,
              snapshot.legacyOrder());
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
    List<String> accepted = new ArrayList<>();
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
        validateConfirm(occurrences.get(key), decision);
      } else if ("approve-corrected-order".equals(decisionType)) {
        validateCorrectedOrder(inventory, key, decisionsPath, decision);
      } else {
        throw new DecisionValidationException("unsupported-decision", "unsupported decision: " + decisionType);
      }
      accepted.add(key);
    }
    return new DecisionSet(List.copyOf(accepted));
  }

  private static void validateConfirm(
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
  }

  private static void validateCorrectedOrder(
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
              snapshots.add(readSnapshot(published));
            }
          }
        }
      }
    } catch (IOException error) {
      throw new UncheckedIOException("cannot scan review workspace", error);
    }
    return List.copyOf(snapshots);
  }

  private static ApprovedSnapshot readSnapshot(Path published) {
    SafeLeaf references = readSafeLeaf(published.resolve("references.json"));
    String pageRef = null;
    String sourcePath = published.getParent().getFileName() + ".md";
    if (references.safe()) {
      try {
        PageReferenceMap map = PageReferenceMapCodec.read(references.bytes(), published.resolve("references.json").toString());
        pageRef = map.pageRef();
        sourcePath = map.sourcePath();
        List<String> order = map.order();
        SafeLeaf ru = readSafeLeaf(published.resolve("ru.md"));
        SafeLeaf en = readSafeLeaf(published.resolve("en.md"));
        String unsafe = firstUnsafe(ru, en, references);
        if (unsafe != null) {
          return unsafeSnapshot(published, pageRef, sourcePath, unsafe, order);
        }
        return new ApprovedSnapshot(
            pageRef,
            sourcePath,
            order,
            ReferenceMigrationAligner.ApprovedDocument.valid(published.resolve("ru.md").toString(), ru.bytes()),
            ReferenceMigrationAligner.ApprovedDocument.valid(published.resolve("en.md").toString(), en.bytes()));
      } catch (RuntimeException error) {
        references = SafeLeaf.unsafe("unsafe approved snapshot references.json: " + error.getMessage());
      }
    }
    SafeLeaf ru = readSafeLeaf(published.resolve("ru.md"));
    SafeLeaf en = readSafeLeaf(published.resolve("en.md"));
    String unsafe = firstUnsafe(ru, en, references);
    if (unsafe != null) {
      return unsafeSnapshot(published, pageRef, sourcePath, unsafe, List.of());
    }
    return new ApprovedSnapshot(
        pageRef,
        sourcePath,
        List.of(),
        ReferenceMigrationAligner.ApprovedDocument.valid(published.resolve("ru.md").toString(), ru.bytes()),
        ReferenceMigrationAligner.ApprovedDocument.valid(published.resolve("en.md").toString(), en.bytes()));
  }

  private static ApprovedSnapshot unsafeSnapshot(
      Path published,
      String pageRef,
      String sourcePath,
      String unsafe,
      List<String> order) {
    return new ApprovedSnapshot(
        pageRef,
        sourcePath,
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

  private static Map<String, ReferenceMigrationAligner.RoutePair> scanAstroRoutes(Path astro) {
    if (astro == null || !Files.isDirectory(astro, LinkOption.NOFOLLOW_LINKS)) {
      return Map.of();
    }
    Map<String, RouteBuilder> routes = new LinkedHashMap<>();
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
          builder.ru = route;
        } else if (route.startsWith("/en/")) {
          builder.en = route;
        }
      }
    } catch (IOException error) {
      return Map.of();
    }
    LinkedHashMap<String, ReferenceMigrationAligner.RoutePair> result = new LinkedHashMap<>();
    for (Map.Entry<String, RouteBuilder> entry : routes.entrySet()) {
      result.put(entry.getKey(), new ReferenceMigrationAligner.RoutePair(entry.getValue().ru, entry.getValue().en));
    }
    return Map.copyOf(result);
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
      String pageRef,
      String sourcePath,
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

  private static final class RouteBuilder {
    private String ru;
    private String en;
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
      payload.put("sourceOrdinal", occurrence.sourceOrdinal());
      payload.put("targetRef", occurrence.targetRef());
      payload.put("heading", occurrence.heading());
      payload.put("reason", occurrence.reason());
      payload.put("proposedReference", proposedReferencePayload(
          occurrence.proposedReferenceId(),
          occurrence.proposedReference()));
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

  public record DecisionSet(List<String> keys) {
    public DecisionSet {
      keys = List.copyOf(keys);
    }
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
