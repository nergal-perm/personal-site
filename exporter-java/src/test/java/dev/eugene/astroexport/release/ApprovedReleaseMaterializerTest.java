package dev.eugene.astroexport.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import dev.eugene.astroexport.review.ApprovedPageSnapshot;
import dev.eugene.astroexport.review.SnapshotHashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ApprovedReleaseMaterializerTest {
  @TempDir
  Path vault;

  private final ApprovedReleaseMaterializer materializer = new ApprovedReleaseMaterializer();

  @Test
  void activatingTargetChangesOutputButNotReferrerApprovedHashes() throws Exception {
    ApprovedPageSnapshot a = approved(
        "A", "[Б](ref:ref-0001)", "[B](ref:ref-0001)",
        reference("ref-0001", "vault-ref-b"));
    SnapshotHashes before = a.hashes();

    ApprovedReleaseMaterializer.MaterializedRelease privateTarget =
        materialize(List.of(a));
    ApprovedReleaseMaterializer.MaterializedRelease publicTarget =
        materialize(List.of(a, approvedTargetB()));

    assertEquals("Б", body(privateTarget, "A", "ru"));
    assertEquals("[Б](/ru/notes/b/)", body(publicTarget, "A", "ru"));
    assertEquals("[B](/en/notes/b/)", body(publicTarget, "A", "en"));
    assertEquals(before, a.hashes());
  }

  @Test
  void duplicateTargetOccurrencesUseIdsAndKeepStrictOrder() throws Exception {
    ApprovedPageSnapshot a = approvedWithReferences(
        "A",
        "[b](ref:ref-0007) [c](ref:ref-0008) [b2](ref:ref-0009)",
        "[b](ref:ref-0007) [c](ref:ref-0008) [b2](ref:ref-0009)",
        List.of("ref-0007", "ref-0008", "ref-0009"),
        references(
            reference("ref-0007", "vault-ref-b"),
            reference("ref-0008", "vault-ref-c", "#Limits"),
            reference("ref-0009", "vault-ref-b", "#Experiments")));

    ApprovedReleaseMaterializer.ActivationAudit audit = materializer
        .materialize(prepareSources(List.of(a, approvedTargetB(), approvedTargetC())), vault)
        .audit();

    assertEquals(List.of("ref-0007", "ref-0008", "ref-0009"),
        audit.forPage(a.pageRef()).stream()
            .map(ApprovedReleaseMaterializer.Activation::occurrenceId).toList());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 20, 100})
  void impactIndexCountsInboundOccurrences(int count) {
    ApprovedPageSnapshot a = approvedWithRepeatedTarget(count);

    ReferenceImpactIndex index = ReferenceImpactIndex.from(List.of(a, approvedTargetB()));

    assertEquals(count, index.inboundTo("vault-ref-b").size());
    assertEquals(List.of(0, count - 1), List.of(
        index.inboundTo("vault-ref-b").getFirst().orderIndex(),
        index.inboundTo("vault-ref-b").getLast().orderIndex()));
  }

  @Test
  void headingFragmentsAreNormalizedOntoBothLanguageRoutes() throws Exception {
    ApprovedPageSnapshot a = approved(
        "A", "[Б](ref:ref-0001# Big Section!)", "[B](ref:ref-0001# Big Section!)",
        reference("ref-0001", "vault-ref-b", "# Ignored Stored Heading"));

    ApprovedReleaseMaterializer.MaterializedRelease release =
        materialize(List.of(a, approvedTargetB()));

    assertEquals("[Б](/ru/notes/b/#big-section)", body(release, "A", "ru"));
    assertEquals("[B](/en/notes/b/#big-section)", body(release, "A", "en"));
  }

  @Test
  void routeChangesAreLateBoundWithoutChangingReferrerHashes() throws Exception {
    ApprovedPageSnapshot a = approved(
        "A", "[Б](ref:ref-0001)", "[B](ref:ref-0001)",
        reference("ref-0001", "vault-ref-b"));
    SnapshotHashes before = a.hashes();

    ApprovedReleaseMaterializer.MaterializedRelease first =
        materialize(List.of(a, approvedTargetB()));
    ApprovedReleaseMaterializer.MaterializedRelease renamed =
        materialize(List.of(a, approvedTarget("B", "vault-ref-b", "renamed")));

    assertEquals("[Б](/ru/notes/b/)", body(first, "A", "ru"));
    assertEquals("[Б](/ru/notes/renamed/)", body(renamed, "A", "ru"));
    assertEquals(before, a.hashes());
  }

  @Test
  void unpublishAndRepublishOnlyChangesMaterializedProjection() throws Exception {
    ApprovedPageSnapshot a = approved(
        "A", "[Б](ref:ref-0001)", "[B](ref:ref-0001)",
        reference("ref-0001", "vault-ref-b"));
    ApprovedPageSnapshot b = approvedTargetB();
    SnapshotHashes before = a.hashes();
    Path approvedB = vault.resolve("review/blog/b/published/ru.md");
    Files.createDirectories(approvedB.getParent());
    Files.writeString(approvedB, b.russian().body(), StandardCharsets.UTF_8);

    ApprovedReleaseMaterializer.MaterializedRelease published =
        materialize(List.of(a, b));
    ApprovedReleaseMaterializer.MaterializedRelease unpublished =
        materialize(List.of(a));
    ApprovedReleaseMaterializer.MaterializedRelease republished =
        materialize(List.of(a, b));

    assertEquals("[Б](/ru/notes/b/)", body(published, "A", "ru"));
    assertEquals("Б", body(unpublished, "A", "ru"));
    assertEquals("[Б](/ru/notes/b/)", body(republished, "A", "ru"));
    assertEquals(before, a.hashes());
    assertEquals(b.russian().body(), Files.readString(approvedB, StandardCharsets.UTF_8));
  }

  @Test
  void publicOutputGateRejectsLeakedSemanticDestinationsAndLocaleRoutes() {
    ApprovedPageSnapshot a = approved(
        "A", "[Б](ref:ref-0001)", "[B](ref:ref-0001)",
        reference("ref-0001", "vault-ref-b"));
    ApprovedPageSnapshot badTarget = approvedTarget("B", "vault-ref-b", "b",
        "/ru/notes/b/", "/ru/notes/b/");

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> materialize(List.of(a, badTarget)));

    assertEquals("invalid-release-output", error.code());
  }

  @Test
  void publicOutputGateRejectsAbsoluteVaultPaths() throws Exception {
    Path source = vault.resolve("source/A.md");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "selected source", StandardCharsets.UTF_8);
    ApprovedPageSnapshot a = withRussianBody(
        withSourcePath(approvedTarget("A", "vault-ref-a", "a"), source.toString()),
        "Leaked /Users/eugene/Documents/personal-wiki/knowledge-base/private/Note.md");

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> materializer.materialize(
            List.of(a),
            Path.of("/Users/eugene/Documents/personal-wiki/knowledge-base")));

    assertEquals("invalid-release-output", error.code());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/ru/private/Note.md", "/ru/private/", "/ru/private/Secret"})
  void publicOutputGateRejectsPathsInsideVaultRootEvenWithoutAllowlistedExtensions(
      String leakedPath) throws Exception {
    Path source = vault.resolve("source/A.md");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "selected source", StandardCharsets.UTF_8);
    ApprovedPageSnapshot a = withRussianBody(
        withSourcePath(approvedTarget("A", "vault-ref-a", "a"), source.toString()),
        "Leaked " + leakedPath);

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> materializer.materialize(List.of(a), Path.of("/ru")));

    assertEquals("invalid-release-output", error.code());
  }

  @Test
  void publicOutputGateAllowsApprovedRoutesInsideVaultRoot() throws Exception {
    Path aSource = vault.resolve("source/A.md");
    Path bSource = vault.resolve("source/B.md");
    Files.createDirectories(aSource.getParent());
    Files.writeString(aSource, "selected source A", StandardCharsets.UTF_8);
    Files.writeString(bSource, "selected source B", StandardCharsets.UTF_8);
    ApprovedPageSnapshot a = withSourcePath(approved(
        "A", "[Б](ref:ref-0001)", "[B](ref:ref-0001)",
        reference("ref-0001", "vault-ref-b")), aSource.toString());
    ApprovedPageSnapshot b = withSourcePath(approvedTargetB(), bSource.toString());

    ApprovedReleaseMaterializer.MaterializedRelease release =
        materializer.materialize(List.of(a, b), Path.of("/ru"));

    assertEquals("[Б](/ru/notes/b/)", body(release, "A", "ru"));
    assertEquals("[B](/en/notes/b/)", body(release, "A", "en"));
  }

  @Test
  void publicOutputGateAllowsPublicRootRelativeLinksOutsideVaultRoot() throws Exception {
    ApprovedPageSnapshot a = withRussianBody(
        approvedTarget("A", "vault-ref-a", "a"),
        "[About](/about/) [Feed](/feed.xml) [Podcast](/podcast/)");

    ApprovedReleaseMaterializer.MaterializedRelease release = materialize(List.of(a));

    assertEquals("[About](/about/) [Feed](/feed.xml) [Podcast](/podcast/)", body(release, "a", "ru"));
  }

  @Test
  void registryRejectsDuplicateApprovedRoutes() {
    ApprovedPageSnapshot a = approvedTarget("A", "vault-ref-a", "a", "/ru/notes/same/", "/en/notes/a/");
    ApprovedPageSnapshot b = approvedTarget("B", "vault-ref-b", "b", "/ru/notes/same/", "/en/notes/b/");

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        () -> ApprovedTargetRegistry.from(List.of(a, b)));

    assertEquals("duplicate-target", error.code());
  }

  @Test
  void inputGuardAbortsWhenSelectedSourceBytesChange() throws Exception {
    Files.createDirectories(vault.resolve("blog"));
    Files.writeString(vault.resolve("blog/A.md"), "before", StandardCharsets.UTF_8);
    ApprovedReleaseMaterializer.MaterializedRelease release =
        materializer.materialize(List.of(approvedTarget("A", "vault-ref-a", "a")), vault);
    Files.writeString(vault.resolve("blog/A.md"), "after", StandardCharsets.UTF_8);

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        release.inputGuard()::verify);

    assertEquals("release-input-changed", error.code());
  }

  @Test
  void inputGuardAbortsWhenApprovedSnapshotLeafChanges() throws Exception {
    Path approvedRu = vault.resolve("review/blog/a/published/ru.md");
    Path approvedEn = vault.resolve("review/blog/a/published/en.md");
    Path approvedReferences = vault.resolve("review/blog/a/published/references.json");
    Files.createDirectories(approvedRu.getParent());
    Files.writeString(approvedRu, "approved ru", StandardCharsets.UTF_8);
    Files.writeString(approvedEn, "approved en", StandardCharsets.UTF_8);
    Files.writeString(approvedReferences, "{}", StandardCharsets.UTF_8);
    ApprovedPageSnapshot snapshot = approvedTarget("A", "vault-ref-a", "a")
        .withInputFiles(new ApprovedPageSnapshot.InputFiles(
            new ApprovedPageSnapshot.InputFile(approvedRu, Files.readAllBytes(approvedRu)),
            new ApprovedPageSnapshot.InputFile(approvedEn, Files.readAllBytes(approvedEn)),
            new ApprovedPageSnapshot.InputFile(approvedReferences, Files.readAllBytes(approvedReferences)),
            null));
    ApprovedReleaseMaterializer.MaterializedRelease release =
        materialize(List.of(snapshot));
    Files.writeString(approvedRu, "replacement", StandardCharsets.UTF_8);

    ApprovedReleaseException error = assertThrows(
        ApprovedReleaseException.class,
        release.inputGuard()::verify);

    assertEquals("release-input-changed", error.code());
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

  private ApprovedReleaseMaterializer.MaterializedRelease materialize(
      List<ApprovedPageSnapshot> snapshots) throws Exception {
    return materializer.materialize(prepareSources(snapshots), vault);
  }

  private List<ApprovedPageSnapshot> prepareSources(List<ApprovedPageSnapshot> snapshots) throws Exception {
    for (ApprovedPageSnapshot snapshot : snapshots) {
      Path source = vault.resolve(snapshot.sourcePath());
      Files.createDirectories(source.getParent());
      if (!Files.exists(source)) {
        Files.writeString(source, "source " + snapshot.sourcePath(), StandardCharsets.UTF_8);
      }
    }
    return snapshots;
  }

  private static ApprovedPageSnapshot approvedTargetB() {
    return approvedTarget("B", "vault-ref-b", "b");
  }

  private static ApprovedPageSnapshot approvedTargetC() {
    return approvedTarget("C", "vault-ref-c", "c");
  }

  private static ApprovedPageSnapshot approvedTarget(String title, String pageRef, String publicId) {
    return approvedTarget(title, pageRef, publicId, "/ru/notes/" + publicId + "/", "/en/notes/" + publicId + "/");
  }

  private static ApprovedPageSnapshot approvedTarget(
      String title,
      String pageRef,
      String publicId,
      String ruRoute,
      String enRoute) {
    return approvedWithReferences(
        title,
        title + " body",
        title + " body",
        List.of(),
        Map.of(),
        pageRef,
        publicId,
        ruRoute,
        enRoute);
  }

  private static ApprovedPageSnapshot withRussianBody(ApprovedPageSnapshot snapshot, String body) {
    return new ApprovedPageSnapshot(
        snapshot.collection(),
        snapshot.publicId(),
        snapshot.pageRef(),
        snapshot.sourcePath(),
        new ManifestEntry(
            snapshot.russian().sourcePath(),
            snapshot.russian().targetPath(),
            snapshot.russian().route(),
            snapshot.russian().metadata(),
            body,
            snapshot.russian().translationSourceHash(),
            snapshot.russian().translationSourceMetadata()),
        snapshot.english(),
        snapshot.references(),
        snapshot.hashes(),
        snapshot.inputFiles());
  }

  private static ApprovedPageSnapshot withSourcePath(ApprovedPageSnapshot snapshot, String sourcePath) {
    return new ApprovedPageSnapshot(
        snapshot.collection(),
        snapshot.publicId(),
        snapshot.pageRef(),
        sourcePath,
        new ManifestEntry(
            sourcePath,
            snapshot.russian().targetPath(),
            snapshot.russian().route(),
            snapshot.russian().metadata(),
            snapshot.russian().body(),
            snapshot.russian().translationSourceHash(),
            snapshot.russian().translationSourceMetadata()),
        new ManifestEntry(
            sourcePath,
            snapshot.english().targetPath(),
            snapshot.english().route(),
            snapshot.english().metadata(),
            snapshot.english().body(),
            snapshot.english().translationSourceHash(),
            snapshot.english().translationSourceMetadata()),
        snapshot.references(),
        snapshot.hashes(),
        snapshot.inputFiles());
  }

  private static ApprovedPageSnapshot approved(
      String title,
      String ru,
      String en,
      Map.Entry<String, PageReferenceMap.Reference> reference) {
    return approvedWithReferences(
        title,
        ru,
        en,
        List.of(reference.getKey()),
        references(reference));
  }

  private static ApprovedPageSnapshot approvedWithRepeatedTarget(int count) {
    List<String> order = java.util.stream.IntStream.range(0, count)
        .mapToObj(index -> "ref-%04d".formatted(index + 1))
        .toList();
    LinkedHashMap<String, PageReferenceMap.Reference> refs = new LinkedHashMap<>();
    StringBuilder body = new StringBuilder();
    for (String id : order) {
      refs.put(id, new PageReferenceMap.Reference("vault-ref-b", "B", "", "B"));
      body.append("[B](ref:").append(id).append(") ");
    }
    return approvedWithReferences("A", body.toString(), body.toString(), order, refs);
  }

  private static ApprovedPageSnapshot approvedWithReferences(
      String title,
      String ru,
      String en,
      List<String> order,
      Map<String, PageReferenceMap.Reference> references) {
    return approvedWithReferences(
        title,
        ru,
        en,
        order,
        references,
        "vault-ref-" + title.toLowerCase(),
        title,
        "/ru/notes/" + title.toLowerCase() + "/",
        "/en/notes/" + title.toLowerCase() + "/");
  }

  private static ApprovedPageSnapshot approvedWithReferences(
      String title,
      String ru,
      String en,
      List<String> order,
      Map<String, PageReferenceMap.Reference> references,
      String pageRef,
      String publicId,
      String ruRoute,
      String enRoute) {
    byte[] ruBytes = ru.getBytes(StandardCharsets.UTF_8);
    byte[] enBytes = en.getBytes(StandardCharsets.UTF_8);
    PageReferenceMap map = new PageReferenceMap(
        PageReferenceMap.SCHEMA_VERSION,
        pageRef,
        "blog/" + title + ".md",
        PageReferenceMapCodec.sha256(ruBytes),
        PageReferenceMapCodec.sha256(enBytes),
        order,
        references);
    return new ApprovedPageSnapshot(
        "blog",
        publicId,
        pageRef,
        "blog/" + title + ".md",
        entry(publicId, ruRoute, "src/content/blog/ru/" + publicId + ".md", ru),
        entry(publicId, enRoute, "src/content/blog/en/" + publicId + ".md", en),
        map,
        new SnapshotHashes(map.ruSha256(), map.enSha256()));
  }

  private static ManifestEntry entry(String publicId, String route, String targetPath, String body) {
    return new ManifestEntry(
        "blog/" + publicId + ".md",
        targetPath,
        route,
        Map.of("id", publicId, "reviewType", "note", "route", route),
        body);
  }

  private static Map.Entry<String, PageReferenceMap.Reference> reference(String id, String targetRef) {
    return reference(id, targetRef, "");
  }

  private static Map.Entry<String, PageReferenceMap.Reference> reference(
      String id,
      String targetRef,
      String heading) {
    return Map.entry(id, new PageReferenceMap.Reference(targetRef, targetRef, heading, targetRef));
  }

  @SafeVarargs
  private static Map<String, PageReferenceMap.Reference> references(
      Map.Entry<String, PageReferenceMap.Reference>... entries) {
    LinkedHashMap<String, PageReferenceMap.Reference> references = new LinkedHashMap<>();
    for (Map.Entry<String, PageReferenceMap.Reference> entry : entries) {
      references.put(entry.getKey(), entry.getValue());
    }
    return references;
  }
}
