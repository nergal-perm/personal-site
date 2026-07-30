package dev.eugene.astroexport.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.fs.AtomicExchange;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class SemanticMigrationServiceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir
  Path temp;

  @Test
  void activationMarkerIsWrittenOnlyAfterEveryTripleIsInstalled() throws Exception {
    Fixture fixture = fixture(3);
    SemanticMigrationService.MigrationHooks hooks =
        SemanticMigrationService.MigrationHooks.failOn(
            SemanticMigrationService.Boundary.PAGE_INSTALLED,
            2);

    assertThrows(
        SemanticMigrationService.MigrationIncompleteException.class,
        () -> new SemanticMigrationService().apply(fixture.request(), hooks));

    assertFalse(Files.exists(SemanticSchemaState.activationMarker(fixture.review())));
    assertEquals(List.of("installed", "installed", "staged"),
        journalPageStates(fixture.review()));
    assertBuildBlocked(fixture.review());
  }

  @Test
  void rollBackRestoresEveryLegacyPairByteForByte() throws Exception {
    Fixture fixture = fixture(3);
    Map<String, byte[]> before = publishedBytes(fixture.review());
    SemanticMigrationService.MigrationHooks hooks =
        SemanticMigrationService.MigrationHooks.failOn(
            SemanticMigrationService.Boundary.PAGE_INSTALLED,
            2);
    assertThrows(
        SemanticMigrationService.MigrationIncompleteException.class,
        () -> new SemanticMigrationService().apply(fixture.request(), hooks));

    new SemanticMigrationService().recover(
        SemanticMigrationService.RecoveryRequest.rollBack(fixture.review()));

    assertEquals(before.keySet(), publishedBytes(fixture.review()).keySet());
    for (Map.Entry<String, byte[]> entry : before.entrySet()) {
      assertArrayEquals(entry.getValue(), Files.readAllBytes(fixture.review().resolve(entry.getKey())));
    }
    assertFalse(Files.exists(SemanticSchemaState.activationMarker(fixture.review())));
    assertEquals(SemanticSchemaState.Mode.LEGACY, SemanticSchemaState.mode(fixture.review()));
  }

  @ParameterizedTest
  @EnumSource(value = SemanticMigrationService.Boundary.class, names = {
      "CATALOG_STAGED",
      "CATALOG_INSTALLED",
      "PAGE_STAGED",
      "PAGE_INSTALLED",
      "PARITY_PROJECTED",
      "ASTRO_GATED",
      "MARKER_WRITE",
      "JOURNAL_FORCED",
      "DISPLACED_CLEANUP",
      "LOCK_RELEASE"
  })
  void failureBoundariesLeaveJournaledRecoveryEvidence(
      SemanticMigrationService.Boundary boundary) throws Exception {
    Fixture fixture = fixture(2);
    SemanticMigrationService.MigrationHooks hooks =
        SemanticMigrationService.MigrationHooks.failOn(boundary, 1);

    SemanticMigrationService.MigrationIncompleteException error = assertThrows(
        SemanticMigrationService.MigrationIncompleteException.class,
        () -> new SemanticMigrationService().apply(fixture.request(), hooks));

    assertTrue(error.getMessage().contains("migration-v1.journal.json"));
    assertTrue(error.getMessage().contains("recovery"));
    Map<String, Object> journal = journal(fixture.review());
    assertEquals(1, journal.get("schemaVersion"));
    assertTrue(journal.containsKey("pages"));
    if (boundary.ordinal() < SemanticMigrationService.Boundary.MARKER_WRITE.ordinal()) {
      assertFalse(Files.exists(SemanticSchemaState.activationMarker(fixture.review())));
      assertBuildBlocked(fixture.review());
    }
  }

  @Test
  void rollForwardResumesFromRecordedStagedBytesAndActivatesLast() throws Exception {
    Fixture fixture = fixture(2);
    assertThrows(
        SemanticMigrationService.MigrationIncompleteException.class,
        () -> new SemanticMigrationService().apply(
            fixture.request(),
            SemanticMigrationService.MigrationHooks.failOn(
                SemanticMigrationService.Boundary.PAGE_INSTALLED,
                1)));

    new SemanticMigrationService().recover(
        SemanticMigrationService.RecoveryRequest.rollForward(fixture.review()));

    assertEquals(SemanticSchemaState.Mode.SEMANTIC, SemanticSchemaState.mode(fixture.review()));
    Map<String, Object> journal = journal(fixture.review());
    assertEquals("complete", journal.get("state"));
    assertTrue(Files.exists(SemanticSchemaState.activationMarker(fixture.review())));
    assertTrue(Files.exists(fixture.review().resolve("blog/page-1/published/references.json")));
    assertTrue(Files.exists(fixture.review().resolve("blog/page-2/published/references.json")));
  }

  @Test
  void rollBackRecoversCrashAfterExchangeBeforeInstalledJournalWrite() throws Exception {
    Fixture fixture = fixture(1);
    Map<String, byte[]> before = publishedBytes(fixture.review());
    SemanticMigrationService service = new SemanticMigrationService(
        new ReferenceMigrationInventory(),
        new FailingAfterExchange(),
        Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC));
    assertThrows(
        SemanticMigrationService.MigrationIncompleteException.class,
        () -> service.apply(fixture.request(), SemanticMigrationService.MigrationHooks.none()));
    assertEquals(List.of("installing"), journalPageStates(fixture.review()));
    assertTrue(Files.readString(fixture.review().resolve("blog/page-1/published/references.json"))
        .contains("ref-"));

    new SemanticMigrationService().recover(
        SemanticMigrationService.RecoveryRequest.rollBack(fixture.review()));

    assertEquals(before.keySet(), publishedBytes(fixture.review()).keySet());
    for (Map.Entry<String, byte[]> entry : before.entrySet()) {
      assertArrayEquals(entry.getValue(), Files.readAllBytes(fixture.review().resolve(entry.getKey())));
    }
    assertEquals(SemanticSchemaState.Mode.LEGACY, SemanticSchemaState.mode(fixture.review()));
  }

  @Test
  void applyRejectsExistingIncompleteJournalWithoutReplacingRecoveryBytes() throws Exception {
    Fixture fixture = fixture(1);
    Path staged = fixture.review().resolve(".semantic-links/staging-v1/blog/page-1/published");
    Files.createDirectories(staged);
    Files.writeString(staged.resolve("sentinel.txt"), "keep", StandardCharsets.UTF_8);
    Files.createDirectories(SemanticSchemaState.migrationJournal(fixture.review()).getParent());
    Files.writeString(SemanticSchemaState.migrationJournal(fixture.review()), """
        {"schemaVersion":1,"state":"planned","inventorySha256":"%s","catalogSha256":"%s","recoveryRoot":".semantic-links/recovery-v1","pages":[]}
        """.formatted("a".repeat(64), "b".repeat(64)), StandardCharsets.UTF_8);

    assertThrows(
        SemanticMigrationService.MigrationIncompleteException.class,
        () -> new SemanticMigrationService().apply(
            fixture.request(),
            SemanticMigrationService.MigrationHooks.none()));

    assertEquals("keep", Files.readString(staged.resolve("sentinel.txt"), StandardCharsets.UTF_8));
  }

  @Test
  void journalRecordsStagedCatalogAndRollForwardValidatesIt() throws Exception {
    Fixture fixture = fixture(1);
    assertThrows(
        SemanticMigrationService.MigrationIncompleteException.class,
        () -> new SemanticMigrationService().apply(
            fixture.request(),
            SemanticMigrationService.MigrationHooks.failOn(
                SemanticMigrationService.Boundary.CATALOG_STAGED,
                1)));
    Map<String, Object> journal = journal(fixture.review());
    assertTrue(((String) journal.get("catalogStaged")).endsWith("catalog-v1.json"));
    Path stagedCatalog = fixture.review().resolve((String) journal.get("catalogStaged"));
    Files.writeString(stagedCatalog, "tampered", StandardCharsets.UTF_8);

    SemanticMigrationService.MigrationIncompleteException error = assertThrows(
        SemanticMigrationService.MigrationIncompleteException.class,
        () -> new SemanticMigrationService().recover(
            SemanticMigrationService.RecoveryRequest.rollForward(fixture.review())));

    assertTrue(error.getCause().getMessage().contains("catalog"));
  }

  @Test
  void correctedOrderDecisionProvidesInstalledEnglishBytesAndReferenceOrder() throws Exception {
    OrderFixture fixture = orderFixture();

    new SemanticMigrationService().apply(fixture.request(), SemanticMigrationService.MigrationHooks.none());

    String english = Files.readString(
        fixture.review().resolve("blog/page/published/en.md"),
        StandardCharsets.UTF_8);
    PageReferenceMap map = PageReferenceMapCodec.read(
        Files.readAllBytes(fixture.review().resolve("blog/page/published/references.json")),
        "references.json");
    assertTrue(english.contains("First [one](ref:ref-0001), then [two](ref:ref-0002)."));
    assertEquals(List.of("ref-0001", "ref-0002"), map.order());
  }

  @Test
  void completeJournalWithCleanupPendingPageStillActivatesSemanticMode() throws Exception {
    Fixture fixture = fixture(1);
    new SemanticMigrationService().apply(fixture.request(), SemanticMigrationService.MigrationHooks.none());
    Map<String, Object> payload = journal(fixture.review());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pages = (List<Map<String, Object>>) payload.get("pages");
    pages.getFirst().put("state", "cleanup-pending");
    Files.writeString(
        SemanticSchemaState.migrationJournal(fixture.review()),
        JSON.writeValueAsString(payload),
        StandardCharsets.UTF_8);

    assertEquals(SemanticSchemaState.Mode.SEMANTIC, SemanticSchemaState.mode(fixture.review()));
  }

  @Test
  void astroGateRunsAgainstStagedCutoverBeforeActivation() throws Exception {
    Fixture fixture = fixture(1);
    List<Path> gated = new ArrayList<>();

    SemanticMigrationService.MigrationIncompleteException error = assertThrows(
        SemanticMigrationService.MigrationIncompleteException.class,
        () -> new SemanticMigrationService().apply(
            new SemanticMigrationService.ApplyRequest(
                fixture.vault(),
                fixture.review(),
                fixture.astro(),
                fixture.report(),
                fixture.decisions(),
                path -> {
                  gated.add(path);
                  assertTrue(Files.exists(path.resolve("blog/page-1/published/references.json")));
                  throw new IllegalStateException("gate failed");
                }),
            SemanticMigrationService.MigrationHooks.none()));

    assertTrue(error.getCause().getMessage().contains("gate failed"));
    assertEquals(1, gated.size());
    assertFalse(Files.exists(SemanticSchemaState.activationMarker(fixture.review())));
  }

  private static void assertBuildBlocked(Path review) {
    assertEquals(SemanticSchemaState.Mode.MIGRATION_INCOMPLETE,
        SemanticSchemaState.mode(review));
  }

  private static List<String> journalPageStates(Path review) throws Exception {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pages = (List<Map<String, Object>>) journal(review).get("pages");
    return pages.stream().map(page -> (String) page.get("state")).toList();
  }

  private static Map<String, Object> journal(Path review) throws Exception {
    return JSON.readValue(
        Files.readAllBytes(SemanticSchemaState.migrationJournal(review)),
        new TypeReference<>() { });
  }

  private static Map<String, byte[]> publishedBytes(Path review) throws Exception {
    Map<String, byte[]> bytes = new LinkedHashMap<>();
    try (var paths = Files.walk(review)) {
      for (Path path : paths
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().contains("/published/"))
          .filter(path -> !review.relativize(path).startsWith(".semantic-links"))
          .sorted()
          .toList()) {
        bytes.put(review.relativize(path).toString(), Files.readAllBytes(path));
      }
    }
    return bytes;
  }

  private Fixture fixture(int pages) throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    Path astro = temp.resolve("astro");
    Path report = temp.resolve("inventory.json");
    Files.createDirectories(astro);
    for (int index = 1; index <= pages; index++) {
      String slug = "page-" + index;
      writeNote(vault, slug + ".md", "Body " + index + ".");
      writePublishedPair(review, slug, slug + ".md", "vault-ref-" + slug);
    }
    writeCatalog(review, pages);
    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, astro, report);
    Path decisions = temp.resolve("decisions.json");
    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{}}
        """.formatted(inventory.inventorySha256()));
    return new Fixture(vault, review, astro, report, decisions);
  }

  private OrderFixture orderFixture() throws Exception {
    Path vault = temp.resolve("vault-order");
    Path review = temp.resolve("review-order");
    Path astro = temp.resolve("astro-order");
    Path report = temp.resolve("inventory-order.json");
    Files.createDirectories(astro);
    writeNote(vault, "page.md", "[[B|one]] [[C|two]]");
    writeNote(vault, "b.md", "B.");
    writeNote(vault, "c.md", "C.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-b", "b.md", "vault-ref-c", "c.md");
    Path published = review.resolve("blog/page/published");
    Files.createDirectories(published);
    String ru = approved("ru", "page", "First [one](/ru/b/), then [two](/ru/c/).\n");
    String en = approved("en", "page", "First [two](/en/c/), then [one](/en/b/).\n");
    Files.writeString(published.resolve("ru.md"), ru, StandardCharsets.UTF_8);
    Files.writeString(published.resolve("en.md"), en, StandardCharsets.UTF_8);
    Files.writeString(published.resolve("references.json"), JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "pageRef", "vault-ref-page",
        "sourcePath", "page.md",
        "ruSha256", PageReferenceMapCodec.sha256(ru.getBytes(StandardCharsets.UTF_8)),
        "enSha256", PageReferenceMapCodec.sha256(en.getBytes(StandardCharsets.UTF_8)),
        "order", List.of("ref-0002", "ref-0001"),
        "references", Map.of(
            "ref-0001", Map.of("targetRef", "vault-ref-b", "authoredTarget", "B", "heading", "", "label", "one"),
            "ref-0002", Map.of("targetRef", "vault-ref-c", "authoredTarget", "C", "heading", "", "label", "two")))),
        StandardCharsets.UTF_8);
    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, astro, report);
    Path corrected = temp.resolve("corrected-en.md");
    String correctedEnglish = approved("en", "page", "First [one](/en/b/), then [two](/en/c/).\n");
    Files.writeString(corrected, correctedEnglish, StandardCharsets.UTF_8);
    Path decisions = temp.resolve("decisions-order.json");
    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"vault-ref-page/order":{"decision":"approve-corrected-order","correctedEnglishPath":"%s","correctedEnglishSha256":"%s"}}}
        """.formatted(
            inventory.inventorySha256(),
            corrected.getFileName(),
            PageReferenceMapCodec.sha256(correctedEnglish.getBytes(StandardCharsets.UTF_8))),
        StandardCharsets.UTF_8);
    return new OrderFixture(vault, review, astro, report, decisions);
  }

  private static void writeCatalog(Path reviewRoot, String... refsAndPaths) throws Exception {
    Map<String, Object> entries = new LinkedHashMap<>();
    for (int index = 0; index < refsAndPaths.length; index += 2) {
      entries.put(refsAndPaths[index], Map.of(
          "pageRef", refsAndPaths[index],
          "currentPath", refsAndPaths[index + 1],
          "stableNoteId", "",
          "title", title(refsAndPaths[index + 1]),
          "aliases", List.of(),
          "previousPaths", List.of(),
          "state", "active"));
    }
    Path catalog = reviewRoot.resolve(".semantic-links/catalog-v1.json");
    Files.createDirectories(catalog.getParent());
    Files.writeString(catalog, JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "entries", entries)), StandardCharsets.UTF_8);
  }

  private static String title(String path) {
    String stem = path.replace(".md", "");
    int slash = stem.lastIndexOf('/');
    if (slash >= 0) {
      stem = stem.substring(slash + 1);
    }
    return stem.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + stem.substring(1);
  }

  private static void writeNote(Path vault, String path, String body) throws Exception {
    Path note = vault.resolve(path);
    Files.createDirectories(note.getParent());
    Files.writeString(note, body, StandardCharsets.UTF_8);
  }

  private static void writeCatalog(Path review, int pages) throws Exception {
    Map<String, Object> entries = new LinkedHashMap<>();
    for (int index = 1; index <= pages; index++) {
      String slug = "page-" + index;
      entries.put("vault-ref-" + slug, Map.of(
          "pageRef", "vault-ref-" + slug,
          "currentPath", slug + ".md",
          "stableNoteId", "",
          "title", slug,
          "aliases", List.of(),
          "previousPaths", List.of(),
          "state", "active"));
    }
    Path catalog = review.resolve(".semantic-links/catalog-v1.json");
    Files.createDirectories(catalog.getParent());
    Files.writeString(catalog, JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "entries", entries)), StandardCharsets.UTF_8);
  }

  private static void writePublishedPair(
      Path review,
      String publicId,
      String sourcePath,
      String pageRef) throws Exception {
    Path published = review.resolve("blog").resolve(publicId).resolve("published");
    Files.createDirectories(published);
    String ru = approved("ru", publicId, "Body RU " + publicId + ".\n");
    String en = approved("en", publicId, "Body EN " + publicId + ".\n");
    Files.writeString(published.resolve("ru.md"), ru, StandardCharsets.UTF_8);
    Files.writeString(published.resolve("en.md"), en, StandardCharsets.UTF_8);
    Files.writeString(published.resolve("references.json"), JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "pageRef", pageRef,
        "sourcePath", sourcePath,
        "ruSha256", PageReferenceMapCodec.sha256(ru.getBytes(StandardCharsets.UTF_8)),
        "enSha256", PageReferenceMapCodec.sha256(en.getBytes(StandardCharsets.UTF_8)),
        "order", List.of(),
        "references", Map.of())), StandardCharsets.UTF_8);
  }

  private static String approved(String language, String publicId, String body) {
    return """
        ---
        id: %s
        language: %s
        publicId: %s
        publicCollection: blog
        publicContentType: essay
        reviewType: essay
        translationStatus: reviewed
        title: %s %s
        ---
        %s""".formatted(publicId, language, publicId, language, publicId, body);
  }

  private record Fixture(Path vault, Path review, Path astro, Path report, Path decisions) {
    SemanticMigrationService.ApplyRequest request() {
      return new SemanticMigrationService.ApplyRequest(vault, review, astro, report, decisions);
    }
  }

  private record OrderFixture(Path vault, Path review, Path astro, Path report, Path decisions) {
    SemanticMigrationService.ApplyRequest request() {
      return new SemanticMigrationService.ApplyRequest(vault, review, astro, report, decisions);
    }
  }

  private static final class FailingAfterExchange implements AtomicExchange {
    private final AtomicExchange delegate = new CopyingExchange();
    private boolean failed;

    @Override
    public void exchange(Path first, Path second) throws IOException {
      delegate.exchange(first, second);
      if (!failed) {
        failed = true;
        throw new IOException("crash after exchange");
      }
    }
  }

  private static final class CopyingExchange implements AtomicExchange {
    @Override
    public void exchange(Path first, Path second) throws IOException {
      Path temp = Files.createTempDirectory(first.getParent(), ".exchange-");
      moveTree(first, temp.resolve("first"));
      moveTree(second, first);
      moveTree(temp.resolve("first"), second);
      Files.delete(temp);
    }

    private static void moveTree(Path from, Path to) throws IOException {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }
  }
}
