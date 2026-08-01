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
import dev.eugene.astroexport.references.VaultReferenceCatalog;
import java.io.IOException;
import java.nio.ByteBuffer;
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
  void rollbackFailsClosedWhenSuccessfulMigrationHasAlreadyRemovedLegacyRecoveryEvidence() throws Exception {
    Fixture fixture = fixture(1);
    new SemanticMigrationService().apply(fixture.request(), SemanticMigrationService.MigrationHooks.none());

    assertThrows(
        SemanticMigrationService.MigrationIncompleteException.class,
        () -> new SemanticMigrationService().recover(
            SemanticMigrationService.RecoveryRequest.rollBack(fixture.review())));

    assertEquals(SemanticSchemaState.Mode.SEMANTIC, SemanticSchemaState.mode(fixture.review()));
    assertTrue(Files.exists(fixture.review().resolve("blog/page-1/published/references.json")));
    assertTrue(Files.exists(SemanticSchemaState.migrationJournal(fixture.review())));
  }

  @Test
  void applyBootstrapsAndInstallsFirstCatalogFromLegacyPairs() throws Exception {
    Fixture fixture = fixture(2);
    Files.delete(VaultReferenceCatalog.catalogPath(fixture.review()));
    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(fixture.vault(), fixture.review(), fixture.astro(), fixture.report());
    Files.writeString(fixture.decisions(), """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{}}
        """.formatted(inventory.inventorySha256()), StandardCharsets.UTF_8);

    new SemanticMigrationService().apply(fixture.request(), SemanticMigrationService.MigrationHooks.none());

    VaultReferenceCatalog catalog = VaultReferenceCatalog.load(fixture.review());
    assertEquals(2, catalog.entries().size());
    assertTrue(Files.exists(VaultReferenceCatalog.catalogPath(fixture.review())));
    assertTrue(Files.exists(fixture.review().resolve("blog/page-1/published/references.json")));
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

  @Test
  void stagedMaterializedReleaseMustMatchLegacyProjection() throws Exception {
    Fixture fixture = linkedFixture();

    SemanticMigrationService.MigrationIncompleteException error = assertThrows(
        SemanticMigrationService.MigrationIncompleteException.class,
        () -> new SemanticMigrationService().apply(
            fixture.request(),
            new SemanticMigrationService.MigrationHooks() {
              @Override
              public void after(SemanticMigrationService.Boundary boundary, int index) throws IOException {
                if (boundary == SemanticMigrationService.Boundary.PAGE_STAGED && index == 2) {
                  retargetFirstReferenceAndRefreshJournalHash(fixture.review());
                }
              }
            }));

    assertTrue(causeChainContains(error, "parity"), () -> causeChain(error));
    assertFalse(Files.exists(SemanticSchemaState.activationMarker(fixture.review())));
  }

  @Test
  void editorialMigrationPassesMaterializedReleaseParity() throws Exception {
    Fixture fixture = editorialFixture();

    new SemanticMigrationService().apply(fixture.request(), SemanticMigrationService.MigrationHooks.none());

    assertEquals(SemanticSchemaState.Mode.SEMANTIC, SemanticSchemaState.mode(fixture.review()));
    assertTrue(Files.exists(fixture.review().resolve("editorial/home/published/references.json")));
  }

  private static void assertBuildBlocked(Path review) {
    assertEquals(SemanticSchemaState.Mode.MIGRATION_INCOMPLETE,
        SemanticSchemaState.mode(review));
  }

  private static boolean causeChainContains(Throwable error, String text) {
    return causeChain(error).contains(text);
  }

  private static String causeChain(Throwable error) {
    List<String> messages = new ArrayList<>();
    Throwable current = error;
    while (current != null) {
      messages.add(current.getClass().getSimpleName() + ": " + current.getMessage());
      current = current.getCause();
    }
    return String.join("\n", messages);
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
      writePublishedSource(vault, slug + ".md", "blog", slug, "Body " + index + ".");
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

  private Fixture linkedFixture() throws Exception {
    Path vault = temp.resolve("vault-linked");
    Path review = temp.resolve("review-linked");
    Path astro = temp.resolve("astro-linked");
    Path report = temp.resolve("inventory-linked.json");
    Files.createDirectories(astro);
    writePublishedSource(vault, "page-1.md", "blog", "page-1", "See [[page-2|target]].");
    writePublishedSource(vault, "page-2.md", "blog", "page-2", "Target.");
    writeAstroRoute(astro, "src/content/blog/ru/page-1.md", "vault-ref-page-1", "/ru/essays/page-1/");
    writeAstroRoute(astro, "src/content/blog/en/page-1.md", "vault-ref-page-1", "/en/essays/page-1/");
    writeAstroRoute(astro, "src/content/blog/ru/page-2.md", "vault-ref-page-2", "/ru/essays/page-2/");
    writeAstroRoute(astro, "src/content/blog/en/page-2.md", "vault-ref-page-2", "/en/essays/page-2/");
    writeCatalog(review, 2);
    writeLinkedPublishedPair(review);
    writePublishedPair(review, "page-2", "page-2.md", "vault-ref-page-2");
    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, astro, report);
    ReferenceMigrationAligner.MigrationOccurrence occurrence =
        inventory.pages().getFirst().occurrences().getFirst();
    Path decisions = temp.resolve("decisions-linked.json");
    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"%s":{"decision":"confirm","enSpan":{"start":%d,"end":%d}}}}
        """.formatted(
            inventory.inventorySha256(),
            occurrence.occurrenceKey(),
            occurrence.proposedEnSpan().start(),
            occurrence.proposedEnSpan().end()));
    return new Fixture(vault, review, astro, report, decisions);
  }

  private OrderFixture orderFixture() throws Exception {
    Path vault = temp.resolve("vault-order");
    Path review = temp.resolve("review-order");
    Path astro = temp.resolve("astro-order");
    Path report = temp.resolve("inventory-order.json");
    Files.createDirectories(astro);
    writePublishedSource(vault, "page.md", "blog", "page", "[[B|one]] [[C|two]]");
    writePublishedSource(vault, "b.md", "blog", "b", "B.");
    writePublishedSource(vault, "c.md", "blog", "c", "C.");
    writeAstroRoute(astro, "src/content/blog/ru/b.md", "vault-ref-b", "/ru/essays/b/");
    writeAstroRoute(astro, "src/content/blog/en/b.md", "vault-ref-b", "/en/essays/b/");
    writeAstroRoute(astro, "src/content/blog/ru/c.md", "vault-ref-c", "/ru/essays/c/");
    writeAstroRoute(astro, "src/content/blog/en/c.md", "vault-ref-c", "/en/essays/c/");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-b", "b.md", "vault-ref-c", "c.md");
    Path published = review.resolve("blog/page/published");
    Files.createDirectories(published);
    String ru = approved("ru", "page", "First [one](/ru/essays/b/), then [two](/ru/essays/c/).\n");
    String en = approved("en", "page", "First [two](/en/essays/c/), then [one](/en/essays/b/).\n");
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
    writePublishedPair(review, "b", "b.md", "vault-ref-b");
    writePublishedPair(review, "c", "c.md", "vault-ref-c");
    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, astro, report);
    Path corrected = temp.resolve("corrected-en.md");
    String correctedEnglish = approved("en", "page", "First [one](/en/essays/b/), then [two](/en/essays/c/).\n");
    Files.writeString(corrected, correctedEnglish, StandardCharsets.UTF_8);
    Path decisions = temp.resolve("decisions-order.json");
    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"vault-ref-page/order":{"decision":"approve-corrected-order","correctedEnglishPath":"%s","approvedEnglishSha256":"%s","correctedEnglishSha256":"%s"}}}
        """.formatted(
            inventory.inventorySha256(),
            corrected.getFileName(),
            PageReferenceMapCodec.sha256(
                inventory.pages().stream()
                    .filter(page -> page.pageRef().equals("vault-ref-page"))
                    .findFirst().orElseThrow().approvedEnglish().text()
                    .getBytes(StandardCharsets.UTF_8)),
            PageReferenceMapCodec.sha256(correctedEnglish.getBytes(StandardCharsets.UTF_8))),
        StandardCharsets.UTF_8);
    return new OrderFixture(vault, review, astro, report, decisions);
  }

  private Fixture editorialFixture() throws Exception {
    Path vault = temp.resolve("vault-editorial");
    Path review = temp.resolve("review-editorial");
    Path astro = temp.resolve("astro-editorial");
    Path report = temp.resolve("inventory-editorial.json");
    Files.createDirectories(astro);
    writePublishedSource(vault, "home.md", "editorial", "home", "Home body.");
    writeCatalog(review, "vault-ref-home", "home.md");
    writeEditorialPublishedPair(review);
    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, astro, report);
    Path decisions = temp.resolve("decisions-editorial.json");
    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{}}
        """.formatted(inventory.inventorySha256()));
    return new Fixture(vault, review, astro, report, decisions);
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

  private static void writePublishedSource(
      Path vault,
      String path,
      String collection,
      String publicId,
      String body) throws Exception {
    writeNote(vault, path, """
        ---
        publish: true
        publicId: %s
        publicCollection: %s
        publicContentType: essay
        ---
        %s
        """.formatted(publicId, collection, body));
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

  private static void writeLinkedPublishedPair(Path review) throws Exception {
    Path published = review.resolve("blog/page-1/published");
    Files.createDirectories(published);
    String ru = approved("ru", "page-1", "See [target](/ru/essays/page-2/).\n");
    String en = approved("en", "page-1", "See [target](/en/essays/page-2/).\n");
    Files.writeString(published.resolve("ru.md"), ru, StandardCharsets.UTF_8);
    Files.writeString(published.resolve("en.md"), en, StandardCharsets.UTF_8);
    Files.writeString(published.resolve("references.json"), JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "pageRef", "vault-ref-page-1",
        "sourcePath", "page-1.md",
        "ruSha256", PageReferenceMapCodec.sha256(ru.getBytes(StandardCharsets.UTF_8)),
        "enSha256", PageReferenceMapCodec.sha256(en.getBytes(StandardCharsets.UTF_8)),
        "order", List.of("ref-0001"),
        "references", Map.of(
                "ref-0001", Map.of(
                "targetRef", "vault-ref-page-2",
                "authoredTarget", "page-2",
                "heading", "",
                "label", "target")))),
        StandardCharsets.UTF_8);
  }

  private static void writeEditorialPublishedPair(Path review) throws Exception {
    Path published = review.resolve("editorial/home/published");
    Files.createDirectories(published);
    String ru = approvedEditorial("ru", "home", "Home Russian page.\n");
    String en = approvedEditorial("en", "home", "Home page.\n");
    Files.writeString(published.resolve("ru.md"), ru, StandardCharsets.UTF_8);
    Files.writeString(published.resolve("en.md"), en, StandardCharsets.UTF_8);
    Files.writeString(published.resolve("references.json"), JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "pageRef", "vault-ref-home",
        "sourcePath", "home.md",
        "ruSha256", PageReferenceMapCodec.sha256(ru.getBytes(StandardCharsets.UTF_8)),
        "enSha256", PageReferenceMapCodec.sha256(en.getBytes(StandardCharsets.UTF_8)),
        "order", List.of(),
        "references", Map.of())), StandardCharsets.UTF_8);
  }

  private static void writeAstroRoute(
      Path astro,
      String path,
      String pageRef,
      String route) throws IOException {
    Path target = astro.resolve(path);
    Files.createDirectories(target.getParent());
    Files.writeString(target, """
        ---
        pageRef: %s
        route: %s
        ---
        Body.
        """.formatted(pageRef, route), StandardCharsets.UTF_8);
  }

  private static void retargetFirstReferenceAndRefreshJournalHash(Path review) throws IOException {
    Path staged = review.resolve(".semantic-links/staging-v1/blog/page-1/published");
    Path references = staged.resolve("references.json");
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = JSON.readValue(Files.readAllBytes(references), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> refs = (Map<String, Object>) payload.get("references");
    @SuppressWarnings("unchecked")
    Map<String, Object> ref = new LinkedHashMap<>((Map<String, Object>) refs.get("ref-0001"));
    ref.put("targetRef", "vault-ref-page-1");
    refs.put("ref-0001", ref);
    byte[] referenceBytes = JSON.writeValueAsBytes(payload);
    Files.write(references, referenceBytes);

    @SuppressWarnings("unchecked")
    Map<String, Object> journal = JSON.readValue(
        Files.readAllBytes(SemanticSchemaState.migrationJournal(review)),
        Map.class);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pages = (List<Map<String, Object>>) journal.get("pages");
    Map<String, Object> page = pages.stream()
        .filter(candidate -> "page-1".equals(candidate.get("publicId")))
        .findFirst()
        .orElseThrow();
    page.put("stagedSha256", PageReferenceMapCodec.sha256(combined(
        Files.readAllBytes(staged.resolve("ru.md")),
        Files.readAllBytes(staged.resolve("en.md")),
        Files.readAllBytes(references))));
    Files.writeString(
        SemanticSchemaState.migrationJournal(review),
        JSON.writeValueAsString(journal),
        StandardCharsets.UTF_8);
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

  private static String approvedEditorial(String language, String publicId, String body) {
    return """
        ---
        id: %s
        language: %s
        publicId: %s
        publicCollection: editorial
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
