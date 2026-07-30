package dev.eugene.astroexport.migration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
