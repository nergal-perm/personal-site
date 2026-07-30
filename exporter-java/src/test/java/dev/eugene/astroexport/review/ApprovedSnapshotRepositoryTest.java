package dev.eugene.astroexport.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import dev.eugene.astroexport.references.VaultReferenceCatalog;
import dev.eugene.astroexport.references.VaultReferenceCatalog.CatalogEntry;
import dev.eugene.astroexport.release.ApprovedReleaseMaterializer;
import dev.eugene.astroexport.release.ApprovedReleaseException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ApprovedSnapshotRepositoryTest {
  @TempDir
  Path temp;

  private final ApprovedSnapshotRepository repository =
      new ApprovedSnapshotRepository();

  @Test
  void selectedNoteWithoutApprovedTripleBlocksWithItsSourcePath() throws Exception {
    Path review = reviewRoot();
    activateSemanticSchema(review);

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> repository.loadSelected(
            selection("blog/New.md"), review, VaultReferenceCatalog.empty()));

    assertEquals("missing-approved-snapshot", error.code());
    assertEquals("blog/New.md", error.sourcePath());
  }

  @Test
  void pendingCandidateDoesNotReplaceAnApprovedSnapshot() throws Exception {
    Path review = reviewRoot();
    writeApprovedTriple(review, "blog", "a", "blog/A.md", "vault-ref-0001", "approved body");
    writeCandidateTriple(review, "blog", "a", "blog/A.md", "vault-ref-0001", "pending body");

    ApprovedPageSnapshot snapshot = repository
        .loadSelected(selection("blog/A.md"), review, VaultReferenceCatalog.empty())
        .getFirst();

    assertEquals("approved body", snapshot.russian().body());
  }

  @Test
  void malformedPendingMetadataStillSelectsTheLastApprovedSnapshotBySourcePath() throws Exception {
    Path review = reviewRoot();
    writeApprovedTriple(review, "blog", "a", "blog/A.md", "vault-ref-0001", "approved body");

    ApprovedPageSnapshot snapshot = repository
        .loadSelected(discoverExcluded("blog/A.md"), review, VaultReferenceCatalog.empty())
        .getFirst();

    assertEquals("approved body", snapshot.russian().body());
  }

  @Test
  void exactPathLoadsWithoutCatalog() throws Exception {
    Path review = reviewRoot();
    writeApprovedTriple(review, "blog", "a", "blog/A.md", "vault-ref-0001", "approved body");

    ApprovedPageSnapshot snapshot = repository
        .loadSelected(selection("blog/A.md"), review, VaultReferenceCatalog.empty())
        .getFirst();

    assertEquals("blog", snapshot.collection());
    assertEquals("a", snapshot.publicId());
    assertEquals("vault-ref-0001", snapshot.pageRef());
    assertEquals("blog/A.md", snapshot.sourcePath());
    assertEquals("src/content/blog/ru/a.md", snapshot.russian().targetPath());
    assertEquals("src/content/blog/en/a.md", snapshot.english().targetPath());
    assertEquals("/ru/essays/a/", snapshot.russian().route());
    assertEquals("/en/essays/a/", snapshot.english().route());
  }

  @Test
  void confirmedRenameLoadsWithCatalog() throws Exception {
    Path review = reviewRoot();
    writeApprovedTriple(review, "blog", "a", "blog/Old.md", "vault-ref-0001", "approved body");

    ApprovedPageSnapshot snapshot = repository
        .loadSelected(
            selection("blog/New.md"),
            review,
            catalog(entry("vault-ref-0001", "blog/New.md", List.of("blog/Old.md"))))
        .getFirst();

    assertEquals("blog/Old.md", snapshot.sourcePath());
    assertEquals("vault-ref-0001", snapshot.pageRef());
  }

  @Test
  void ambiguousRenameBlocks() throws Exception {
    Path review = reviewRoot();
    writeApprovedTriple(review, "blog", "a", "blog/OldA.md", "vault-ref-0001", "body a");
    writeApprovedTriple(review, "blog", "b", "blog/OldB.md", "vault-ref-0002", "body b");

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> repository.loadSelected(
            selection("blog/New.md"),
            review,
            catalog(
                entry("vault-ref-0001", "blog/New.md", List.of("blog/OldA.md")),
                entry("vault-ref-0002", "blog/New.md", List.of("blog/OldB.md")))));

    assertEquals("ambiguous-approved-snapshot", error.code());
    assertEquals("blog/New.md", error.sourcePath());
  }

  @Test
  void duplicatePageRefBlocksBeforeReturningReleaseInput() throws Exception {
    Path review = reviewRoot();
    writeApprovedTriple(review, "blog", "a", "blog/A.md", "vault-ref-0001", "body a");
    writeApprovedTriple(review, "blog", "b", "blog/B.md", "vault-ref-0001", "body b");

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> repository.loadSelected(
            selection("blog/A.md", "blog/B.md"), review, VaultReferenceCatalog.empty()));

    assertEquals("duplicate-page-ref", error.code());
  }

  @Test
  void duplicatePublicIdBlocksBeforeReturningReleaseInput() throws Exception {
    Path review = reviewRoot();
    writeApprovedTriple(review, "blog", "first-page", "blog/A.md", "vault-ref-0001", "body a", "dup", "essay");
    writeApprovedTriple(review, "blog", "second-page", "blog/B.md", "vault-ref-0002", "body b", "dup", "essay");

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> repository.loadSelected(
            selection("blog/A.md", "blog/B.md"), review, VaultReferenceCatalog.empty()));

    assertEquals("duplicate-public-id", error.code());
  }

  @Test
  void duplicateStoredRouteDoesNotBlockWhenDerivedRoutesDiffer() throws Exception {
    Path review = reviewRoot();
    writeApprovedTriple(review, "blog", "a", "blog/A.md", "vault-ref-0001", "body a", "a", "essay");
    writeApprovedTriple(review, "blog", "b", "blog/B.md", "vault-ref-0002", "body b", "b", "essay", "/ru/essays/a/");

    List<ApprovedPageSnapshot> snapshots = repository.loadSelected(
        selection("blog/A.md", "blog/B.md"), review, VaultReferenceCatalog.empty());

    assertEquals(List.of("/ru/essays/a/", "/ru/essays/b/"), snapshots.stream()
        .map(snapshot -> snapshot.russian().route())
        .toList());
  }

  @Test
  void duplicateStoredTargetPathDoesNotBlockWhenDerivedTargetPathsDiffer() throws Exception {
    Path review = reviewRoot();
    writeApprovedTriple(review, "blog", "a", "blog/A.md", "vault-ref-0001", "body a", "a", "essay");
    writeApprovedTriple(review, "blog", "b", "blog/B.md", "vault-ref-0002", "body b", "b", "essay", "/ru/essays/b/", "src/content/blog/ru/a.md");

    List<ApprovedPageSnapshot> snapshots = repository.loadSelected(
        selection("blog/A.md", "blog/B.md"), review, VaultReferenceCatalog.empty());

    assertEquals(List.of("src/content/blog/ru/a.md", "src/content/blog/ru/b.md"), snapshots.stream()
        .map(snapshot -> snapshot.russian().targetPath())
        .toList());
  }

  @Test
  void invalidHashesBlock() throws Exception {
    Path review = reviewRoot();
    writeApprovedTriple(review, "blog", "a", "blog/A.md", "vault-ref-0001", "approved body");
    Files.writeString(
        review.resolve("blog/a/published/references.json"),
        referencesJson("vault-ref-0001", "blog/A.md", "0".repeat(64), "0".repeat(64)),
        StandardCharsets.UTF_8);

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> repository.loadSelected(selection("blog/A.md"), review, VaultReferenceCatalog.empty()));

    assertEquals("invalid-approved-snapshot", error.code());
    assertEquals("blog/A.md", error.sourcePath());
  }

  @Test
  void wrongSourcePathBlocks() throws Exception {
    Path review = reviewRoot();
    writeApprovedTriple(review, "blog", "a", "blog/Wrong.md", "vault-ref-0001", "approved body");

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> repository.loadSelected(selection("blog/A.md"), review, VaultReferenceCatalog.empty()));

    assertEquals("missing-approved-snapshot", error.code());
    assertEquals("blog/A.md", error.sourcePath());
  }

  @Test
  void unsafePublishedLeavesBlock() throws Exception {
    Path review = reviewRoot();
    writeApprovedTriple(review, "blog", "a", "blog/A.md", "vault-ref-0001", "approved body");
    Files.delete(review.resolve("blog/a/published/en.md"));
    Files.createSymbolicLink(review.resolve("blog/a/published/en.md"), temp.resolve("outside.md"));

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> repository.loadSelected(selection("blog/A.md"), review, VaultReferenceCatalog.empty()));

    assertEquals("invalid-approved-snapshot", error.code());
    assertEquals("blog/A.md", error.sourcePath());
  }

  @Test
  void guardRejectsApprovedLeafReplacedAfterRepositoryLoad() throws Exception {
    Path review = reviewRoot();
    Path vault = temp.resolve("vault");
    Files.createDirectories(vault.resolve("blog"));
    Files.writeString(vault.resolve("blog/A.md"), "selected source", StandardCharsets.UTF_8);
    writeApprovedTriple(review, "blog", "a", "blog/A.md", "vault-ref-0001", "approved body");
    ApprovedPageSnapshot snapshot = repository
        .loadSelected(selection("blog/A.md"), review, VaultReferenceCatalog.empty())
        .getFirst();
    Files.writeString(
        review.resolve("blog/a/published/ru.md"),
        "replacement body",
        StandardCharsets.UTF_8);

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> new ApprovedReleaseMaterializer().materialize(List.of(snapshot), vault));

    assertEquals("release-input-changed", error.code());
  }

  @Test
  void incompleteMigrationJournalBlocks() throws Exception {
    Path review = reviewRoot();
    Path journal = review.resolve(".semantic-links/migration-v1.journal.json");
    Files.createDirectories(journal.getParent());
    Files.writeString(journal, "{\"state\":\"installed\"}", StandardCharsets.UTF_8);

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> repository.loadSelected(selection("blog/A.md"), review, VaultReferenceCatalog.empty()));

    assertEquals("migration-incomplete", error.code());
  }

  private Path reviewRoot() throws Exception {
    Path review = temp.resolve("review");
    Files.createDirectories(review);
    activateSemanticSchema(review);
    return review;
  }

  private static void activateSemanticSchema(Path review) throws Exception {
    String inventory = "a".repeat(64);
    String catalog = "b".repeat(64);
    Path semantic = review.resolve(".semantic-links");
    Files.createDirectories(semantic);
    Files.writeString(semantic.resolve("schema-v1.active.json"), """
        {"schemaVersion":1,"inventorySha256":"%s","catalogSha256":"%s","activatedAt":"%s"}
        """.formatted(inventory, catalog, Instant.parse("2026-07-30T00:00:00Z")));
    Files.writeString(semantic.resolve("migration-v1.journal.json"), """
        {
          "schemaVersion": 1,
          "state": "complete",
          "inventorySha256": "%s",
          "catalogSha256": "%s",
          "catalogState": "complete",
          "catalogPublished": ".semantic-links/catalog-v1.json",
          "catalogStaged": ".semantic-links/staging-v1/catalog-v1.json",
          "catalogDisplaced": ".semantic-links/recovery-v1/catalog-v1.json",
          "recoveryRoot": ".semantic-links/recovery-v1",
          "pages": [
            {"collection":"blog","publicId":"fixture","pageRef":"vault-ref-fixture","sourcePath":"fixture.md","state":"complete","stagedSha256":"%s","published":"blog/fixture/published","staged":".semantic-links/staging-v1/blog/fixture/published","displaced":".semantic-links/recovery-v1/blog/fixture/published"}
          ]
        }
        """.formatted(inventory, catalog, "c".repeat(64)));
  }

  private static SelectionResult selection(String... paths) {
    List<Note> notes = List.of(paths).stream()
        .map(path -> new Note(
            Path.of("/vault").resolve(path),
            path,
            path,
            Map.of("publish", true, "publicId", "ignored"),
            "",
            true,
            "ignored",
            "blog",
            "essay",
            List.of()))
        .toList();
    return new SelectionResult(notes, List.of(), notes.size(), notes.size());
  }

  private static SelectionResult discoverExcluded(String path) {
    SelectionResult.Exclusion exclusion =
        new SelectionResult.Exclusion(Path.of("/vault").resolve(path), "missing publicId", path, "publicId");
    return new SelectionResult(List.of(), List.of(exclusion), 1, 1);
  }

  private static VaultReferenceCatalog catalog(CatalogEntry... entries) {
    LinkedHashMap<String, CatalogEntry> values = new LinkedHashMap<>();
    for (CatalogEntry entry : entries) {
      values.put(entry.pageRef(), entry);
    }
    return new VaultReferenceCatalog(VaultReferenceCatalog.SCHEMA_VERSION, values);
  }

  private static CatalogEntry entry(String pageRef, String currentPath, List<String> previousPaths) {
    return new CatalogEntry(
        pageRef,
        currentPath,
        null,
        "",
        List.of(),
        previousPaths,
        VaultReferenceCatalog.STATE_ACTIVE);
  }

  private static void writeCandidateTriple(
      Path review,
      String collection,
      String directoryPublicId,
      String sourcePath,
      String pageRef,
      String body) throws Exception {
    writeTriple(review, collection, directoryPublicId, "candidate", sourcePath, pageRef, body, directoryPublicId, "essay");
  }

  private static void writeApprovedTriple(
      Path review,
      String collection,
      String directoryPublicId,
      String sourcePath,
      String pageRef,
      String body) throws Exception {
    writeApprovedTriple(review, collection, directoryPublicId, sourcePath, pageRef, body, directoryPublicId, "essay");
  }

  private static void writeApprovedTriple(
      Path review,
      String collection,
      String directoryPublicId,
      String sourcePath,
      String pageRef,
      String body,
      String approvedPublicId,
      String contentType) throws Exception {
    writeTriple(review, collection, directoryPublicId, "published", sourcePath, pageRef, body, approvedPublicId, contentType);
  }

  private static void writeApprovedTriple(
      Path review,
      String collection,
      String directoryPublicId,
      String sourcePath,
      String pageRef,
      String body,
      String approvedPublicId,
      String contentType,
      String route) throws Exception {
    writeTriple(review, collection, directoryPublicId, "published", sourcePath, pageRef, body, approvedPublicId, contentType, route, null);
  }

  private static void writeApprovedTriple(
      Path review,
      String collection,
      String directoryPublicId,
      String sourcePath,
      String pageRef,
      String body,
      String approvedPublicId,
      String contentType,
      String route,
      String targetPath) throws Exception {
    writeTriple(review, collection, directoryPublicId, "published", sourcePath, pageRef, body, approvedPublicId, contentType, route, targetPath);
  }

  private static void writeTriple(
      Path review,
      String collection,
      String directoryPublicId,
      String leafDirectory,
      String sourcePath,
      String pageRef,
      String body,
      String approvedPublicId,
      String contentType) throws Exception {
    writeTriple(review, collection, directoryPublicId, leafDirectory, sourcePath, pageRef, body, approvedPublicId, contentType, null, null);
  }

  private static void writeTriple(
      Path review,
      String collection,
      String directoryPublicId,
      String leafDirectory,
      String sourcePath,
      String pageRef,
      String body,
      String approvedPublicId,
      String contentType,
      String route,
      String targetPath) throws Exception {
    Path directory = review.resolve(collection).resolve(directoryPublicId).resolve(leafDirectory);
    Files.createDirectories(directory);
    String actualRoute = route == null ? route(collection, approvedPublicId, contentType, "ru") : route;
    String actualTargetPath = targetPath == null
        ? targetPath(collection, approvedPublicId, "ru")
        : targetPath;
    byte[] ru = markdown(
        approvedPublicId,
        "ru",
        contentType,
        actualRoute,
        actualTargetPath,
        null,
        body).getBytes(StandardCharsets.UTF_8);
    byte[] en = markdown(
        approvedPublicId,
        "en",
        contentType,
        route(collection, approvedPublicId, contentType, "en"),
        targetPath(collection, approvedPublicId, "en"),
        "reviewed",
        body).getBytes(StandardCharsets.UTF_8);
    Files.write(directory.resolve("ru.md"), ru);
    Files.write(directory.resolve("en.md"), en);
    Files.writeString(directory.resolve("references.json"), referencesJson(
        pageRef,
        sourcePath,
        PageReferenceMapCodec.sha256(ru),
        PageReferenceMapCodec.sha256(en)), StandardCharsets.UTF_8);
  }

  private static String markdown(
      String publicId,
      String language,
      String contentType,
      String route,
      String targetPath,
      String status,
      String body) {
    String statusLine = status == null ? "" : "translationStatus: " + status + "\n";
    return """
        ---
        id: %s
        language: %s
        reviewType: %s
        route: %s
        targetPath: %s
        %s---
        %s
        """.formatted(publicId, language, contentType, route, targetPath, statusLine, body);
  }

  private static String referencesJson(
      String pageRef,
      String sourcePath,
      String ruSha256,
      String enSha256) {
    return new String(PageReferenceMapCodec.write(new PageReferenceMap(
        PageReferenceMap.SCHEMA_VERSION,
        pageRef,
        sourcePath,
        ruSha256,
        enSha256,
        List.of(),
        Map.of())), StandardCharsets.UTF_8);
  }

  private static String route(String collection, String publicId, String contentType, String language) {
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
      default -> throw new IllegalArgumentException(contentType);
    };
    return "/" + language + "/" + section + "/" + publicId + "/";
  }

  private static String targetPath(String collection, String publicId, String language) {
    if ("editorial".equals(collection)) {
      return "src/data/pages/" + language + "/" + publicId + ".json";
    }
    return "src/content/" + collection + "/" + language + "/" + publicId + ".md";
  }
}
