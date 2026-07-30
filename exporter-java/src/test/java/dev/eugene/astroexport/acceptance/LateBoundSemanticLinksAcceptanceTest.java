package dev.eugene.astroexport.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import dev.eugene.astroexport.references.VaultReferenceCatalog;
import dev.eugene.astroexport.release.ApprovedReleaseException;
import dev.eugene.astroexport.release.ApprovedReleaseMaterializer;
import dev.eugene.astroexport.review.ApprovedPageSnapshot;
import dev.eugene.astroexport.review.ApprovedSnapshotRepository;
import dev.eugene.astroexport.review.SnapshotHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LateBoundSemanticLinksAcceptanceTest {
  @TempDir
  Path temp;

  private final ApprovedSnapshotRepository repository = new ApprovedSnapshotRepository();
  private final ApprovedReleaseMaterializer materializer = new ApprovedReleaseMaterializer();

  @Test
  void targetApprovalActivatesBothLanguagesWithoutReferrerWrites() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    activateSemanticSchema(review);
    writeCatalog(review, catalogEntry("vault-ref-a", "blog/A.md"), catalogEntry("vault-ref-b", "blog/B.md"));
    writeSource(vault, "blog/A.md", true);
    writeSource(vault, "blog/B.md", false);
    writeApprovedTriple(
        review,
        "a",
        "blog/A.md",
        "vault-ref-a",
        "B label: [B label](ref:ref-0001)",
        "B label EN: [B label EN](ref:ref-0001)",
        List.of("ref-0001"),
        Map.of("ref-0001", reference("vault-ref-b", "B")));
    TripleHashes aBefore = approvedHashes(review, "a");

    ApprovedReleaseMaterializer.MaterializedRelease first =
        buildApprovedRelease(vault, review, "blog/A.md");
    assertEquals("B label: B label", body(first, "a", "ru"));
    assertEquals("B label EN: B label EN", body(first, "a", "en"));
    assertEquals(List.of("vault-ref-b"), first.ignoredDrafts().stream()
        .map(ApprovedReleaseMaterializer.IgnoredDraft::targetRef)
        .distinct()
        .toList());

    writeSource(vault, "blog/B.md", true);
    ApprovedReleaseException missingApproval = assertThrows(
        ApprovedReleaseException.class,
        () -> repository.loadSelected(
            selection(vault, "blog/A.md", "blog/B.md"),
            review,
            VaultReferenceCatalog.load(review)));
    assertEquals("missing-approved-snapshot", missingApproval.code());
    assertEquals("blog/B.md", missingApproval.sourcePath());

    writeApprovedTriple(
        review,
        "b",
        "blog/B.md",
        "vault-ref-b",
        "B body",
        "B body EN",
        List.of(),
        Map.of());
    ApprovedReleaseMaterializer.MaterializedRelease second =
        buildApprovedRelease(vault, review, "blog/A.md", "blog/B.md");
    assertEquals(aBefore, approvedHashes(review, "a"));
    assertEquals("B label: [B label](/ru/notes/b/)", body(second, "a", "ru"));
    assertEquals("B label EN: [B label EN](/en/notes/b/)", body(second, "a", "en"));
    assertEquals(0, Files.find(review.resolve("blog/a"), 3,
        (path, attributes) -> path.getFileName().toString().startsWith(".published-stage-")).count());
    assertFalse(Files.exists(review.resolve("blog/a/candidate")));

    writeSource(vault, "blog/B.md", false);
    ApprovedReleaseMaterializer.MaterializedRelease unpublished =
        buildApprovedRelease(vault, review, "blog/A.md");
    assertEquals("B label: B label", body(unpublished, "a", "ru"));

    writeSource(vault, "blog/B.md", true);
    ApprovedReleaseMaterializer.MaterializedRelease republished =
        buildApprovedRelease(vault, review, "blog/A.md", "blog/B.md");
    assertEquals("B label: [B label](/ru/notes/b/)", body(republished, "a", "ru"));
    assertEquals("B label EN: [B label EN](/en/notes/b/)", body(republished, "a", "en"));
    assertEquals(aBefore, approvedHashes(review, "a"));
  }

  @Test
  void oneHundredInboundOccurrencesStayStableAcrossTargetApprovalChanges() throws Exception {
    Path vault = temp.resolve("vault-many");
    Path review = temp.resolve("review-many");
    activateSemanticSchema(review);
    List<CatalogFixture> catalog = new ArrayList<>();
    catalog.add(catalogEntry("vault-ref-target", "blog/Target.md"));
    for (int page = 1; page <= 20; page++) {
      String publicId = "referrer-%02d".formatted(page);
      String sourcePath = "blog/Referrer%02d.md".formatted(page);
      String pageRef = "vault-ref-referrer-%02d".formatted(page);
      catalog.add(catalogEntry(pageRef, sourcePath));
      writeSource(vault, sourcePath, true);
      InboundBody inbound = inboundBody(page);
      writeApprovedTriple(
          review,
          publicId,
          sourcePath,
          pageRef,
          inbound.russian(),
          inbound.english(),
          inbound.order(),
          inbound.references());
    }
    writeSource(vault, "blog/Target.md", false);
    writeCatalog(review, catalog.toArray(CatalogFixture[]::new));

    List<String> referrerSources = java.util.stream.IntStream.rangeClosed(1, 20)
        .mapToObj(page -> "blog/Referrer%02d.md".formatted(page))
        .toList();
    LinkedHashMap<String, TripleHashes> before = new LinkedHashMap<>();
    for (int page = 1; page <= 20; page++) {
      before.put("referrer-%02d".formatted(page), approvedHashes(review, "referrer-%02d".formatted(page)));
    }

    ApprovedReleaseMaterializer.MaterializedRelease labelsOnly =
        buildApprovedRelease(vault, review, referrerSources.toArray(String[]::new));
    assertEquals(200, labelsOnly.ignoredDrafts().size());
    assertEquals("RU 01.0 RU 01.1 RU 01.2 RU 01.3 RU 01.4", body(labelsOnly, "referrer-01", "ru"));

    writeSource(vault, "blog/Target.md", true);
    writeApprovedTriple(
        review,
        "target",
        "blog/Target.md",
        "vault-ref-target",
        "Target body",
        "Target body EN",
        List.of(),
        Map.of());
    List<String> withTarget = new ArrayList<>(referrerSources);
    withTarget.add("blog/Target.md");

    ApprovedReleaseMaterializer.MaterializedRelease activated =
        buildApprovedRelease(vault, review, withTarget.toArray(String[]::new));
    assertEquals(100, activated.audit().impactIndex().inboundTo("vault-ref-target").size());
    assertEquals(100, activated.audit().byPageRef().values().stream().mapToInt(List::size).sum());
    assertEquals("[RU 01.0](/ru/notes/target/#section-0) [RU 01.1](/ru/notes/target/#section-1) "
            + "[RU 01.2](/ru/notes/target/#section-2) [RU 01.3](/ru/notes/target/#section-3) "
            + "[RU 01.4](/ru/notes/target/#section-4)",
        body(activated, "referrer-01", "ru"));
    assertEquals("[EN 20.0](/en/notes/target/#section-0) [EN 20.1](/en/notes/target/#section-1) "
            + "[EN 20.2](/en/notes/target/#section-2) [EN 20.3](/en/notes/target/#section-3) "
            + "[EN 20.4](/en/notes/target/#section-4)",
        body(activated, "referrer-20", "en"));
    for (Map.Entry<String, TripleHashes> entry : before.entrySet()) {
      assertEquals(entry.getValue(), approvedHashes(review, entry.getKey()));
    }
  }

  private ApprovedReleaseMaterializer.MaterializedRelease buildApprovedRelease(
      Path vault,
      Path review,
      String... selectedSources) {
    List<ApprovedPageSnapshot> snapshots = repository.loadSelected(
        selection(vault, selectedSources),
        review,
        VaultReferenceCatalog.load(review));
    return materializer.materialize(snapshots, vault);
  }

  private static SelectionResult selection(Path vault, String... paths) {
    List<Note> notes = List.of(paths).stream()
        .map(path -> new Note(
            vault.resolve(path),
            path,
            path,
            Map.of(
                "publish", true,
                "publicId", publicId(path),
                "publicCollection", "blog",
                "publicContentType", "note"),
            "",
            true,
            publicId(path),
            "blog",
            "note",
            List.of()))
        .toList();
    return new SelectionResult(notes, List.of(), notes.size(), notes.size());
  }

  private static void writeSource(Path vault, String sourcePath, boolean publish) throws Exception {
    Path path = vault.resolve(sourcePath);
    Files.createDirectories(path.getParent());
    Files.writeString(path, """
        ---
        publish: %s
        publicId: %s
        publicCollection: blog
        publicContentType: note
        title: %s
        ---
        source body
        """.formatted(publish, publicId(sourcePath), publicId(sourcePath)), StandardCharsets.UTF_8);
  }

  private static void activateSemanticSchema(Path review) throws Exception {
    String inventory = "a".repeat(64);
    String catalog = "b".repeat(64);
    Path semantic = review.resolve(".semantic-links");
    Files.createDirectories(semantic);
    Files.writeString(semantic.resolve("schema-v1.active.json"), """
        {"schemaVersion":1,"inventorySha256":"%s","catalogSha256":"%s","activatedAt":"%s"}
        """.formatted(inventory, catalog, Instant.parse("2026-07-30T00:00:00Z")), StandardCharsets.UTF_8);
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
        """.formatted(inventory, catalog, "c".repeat(64)), StandardCharsets.UTF_8);
  }

  private static void writeCatalog(Path review, CatalogFixture... fixtures) throws Exception {
    LinkedHashMap<String, Object> root = new LinkedHashMap<>();
    root.put("schemaVersion", 1);
    LinkedHashMap<String, Object> entries = new LinkedHashMap<>();
    for (CatalogFixture fixture : fixtures) {
      entries.put(fixture.pageRef(), Map.of(
          "pageRef", fixture.pageRef(),
          "currentPath", fixture.currentPath(),
          "stableNoteId", fixture.pageRef(),
          "title", publicId(fixture.currentPath()),
          "aliases", List.of(),
          "previousPaths", List.of(),
          "state", "active"));
    }
    root.put("entries", entries);
    Files.createDirectories(review.resolve(".semantic-links"));
    byte[] json = new com.fasterxml.jackson.databind.ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValueAsBytes(root);
    Files.write(review.resolve(".semantic-links/catalog-v1.json"), json);
  }

  private static void writeApprovedTriple(
      Path review,
      String publicId,
      String sourcePath,
      String pageRef,
      String russianBody,
      String englishBody,
      List<String> order,
      Map<String, PageReferenceMap.Reference> references) throws Exception {
    Path directory = review.resolve("blog").resolve(publicId).resolve("published");
    Files.createDirectories(directory);
    byte[] russian = markdown(publicId, "ru", russianBody).getBytes(StandardCharsets.UTF_8);
    byte[] english = markdown(publicId, "en", englishBody).getBytes(StandardCharsets.UTF_8);
    PageReferenceMap map = new PageReferenceMap(
        PageReferenceMap.SCHEMA_VERSION,
        pageRef,
        sourcePath,
        PageReferenceMapCodec.sha256(russian),
        PageReferenceMapCodec.sha256(english),
        order,
        references);
    PageReferenceMapCodec.validate(map, russian, english);
    Files.write(directory.resolve("ru.md"), russian);
    Files.write(directory.resolve("en.md"), english);
    Files.write(directory.resolve("references.json"), PageReferenceMapCodec.write(map));
  }

  private static String markdown(String publicId, String language, String body) {
    String status = "en".equals(language) ? "translationStatus: reviewed\n" : "";
    return """
        ---
        id: %s
        language: %s
        reviewType: note
        route: /%s/notes/%s/
        targetPath: src/content/blog/%s/%s.md
        %s---
        %s
        """.formatted(publicId, language, language, publicId, language, publicId, status, body);
  }

  private static PageReferenceMap.Reference reference(String targetRef, String label) {
    return new PageReferenceMap.Reference(targetRef, label, "", label);
  }

  private static InboundBody inboundBody(int page) {
    ArrayList<String> order = new ArrayList<>();
    LinkedHashMap<String, PageReferenceMap.Reference> references = new LinkedHashMap<>();
    StringBuilder ru = new StringBuilder();
    StringBuilder en = new StringBuilder();
    for (int occurrence = 0; occurrence < 5; occurrence++) {
      String id = "ref-%02d-%02d".formatted(page, occurrence);
      String heading = "# Section " + occurrence;
      order.add(id);
      references.put(id, new PageReferenceMap.Reference("vault-ref-target", "Target", heading, "Target"));
      if (!ru.isEmpty()) {
        ru.append(' ');
        en.append(' ');
      }
      ru.append("[RU %02d.%d](ref:%s%s)".formatted(page, occurrence, id, heading));
      en.append("[EN %02d.%d](ref:%s%s)".formatted(page, occurrence, id, heading));
    }
    return new InboundBody(ru.toString(), en.toString(), List.copyOf(order), references);
  }

  private static String body(
      ApprovedReleaseMaterializer.MaterializedRelease release,
      String publicId,
      String language) {
    List<ManifestEntry> entries = "ru".equals(language)
        ? release.manifest().entries()
        : release.manifest().englishEntries();
    return entries.stream()
        .filter(entry -> publicId.equals(entry.metadata().get("id")))
        .findFirst()
        .orElseThrow()
        .body();
  }

  private static TripleHashes approvedHashes(Path review, String publicId) throws Exception {
    Path directory = review.resolve("blog").resolve(publicId).resolve("published");
    return new TripleHashes(
        PageReferenceMapCodec.sha256(Files.readAllBytes(directory.resolve("ru.md"))),
        PageReferenceMapCodec.sha256(Files.readAllBytes(directory.resolve("en.md"))),
        PageReferenceMapCodec.sha256(Files.readAllBytes(directory.resolve("references.json"))));
  }

  private static String publicId(String sourcePath) {
    String name = Path.of(sourcePath).getFileName().toString().replaceFirst("\\.md$", "");
    return name.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
  }

  private static CatalogFixture catalogEntry(String pageRef, String currentPath) {
    return new CatalogFixture(pageRef, currentPath);
  }

  private record CatalogFixture(String pageRef, String currentPath) { }

  private record TripleHashes(String russian, String english, String references) { }

  private record InboundBody(
      String russian,
      String english,
      List<String> order,
      Map<String, PageReferenceMap.Reference> references) { }
}
