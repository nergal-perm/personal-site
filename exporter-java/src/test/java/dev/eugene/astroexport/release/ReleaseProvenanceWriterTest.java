package dev.eugene.astroexport.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.fs.TreeHasher;
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

final class ReleaseProvenanceWriterTest {
  private final ReleaseProvenanceWriter writer = new ReleaseProvenanceWriter();

  @TempDir
  Path temp;

  @Test
  void manifestHashesPayloadWithoutHashingItself() throws Exception {
    ApprovedReleaseMaterializer.MaterializedRelease release = activatedRelease();
    Path stagedSite = stagedPayload(release);

    ReleaseProvenance provenance = writer.write(stagedSite, release);

    assertEquals(TreeHasher.PAYLOAD_ROOTS,
        provenance.managedTrees().stream().map(TreeHasher.ManagedTreeHash::relative).toList());
    assertFalse(provenance.managedFiles().stream()
        .anyMatch(file -> file.path().equals(ReleaseProvenanceWriter.MANIFEST_RELATIVE)));
    assertEquals(provenance, writer.verify(stagedSite));
  }

  @Test
  void writesDeterministicManifestBytes() throws Exception {
    ApprovedReleaseMaterializer.MaterializedRelease release = activatedRelease();
    Path first = stagedPayload(release);
    Path second = stagedPayload(release);

    ReleaseProvenance firstProvenance = writer.write(first, release);
    ReleaseProvenance secondProvenance = writer.write(second, release);

    assertEquals(firstProvenance, secondProvenance);
    assertEquals(
        Files.readString(first.resolve(ReleaseProvenanceWriter.MANIFEST_RELATIVE)),
        Files.readString(second.resolve(ReleaseProvenanceWriter.MANIFEST_RELATIVE)));
  }

  @Test
  void recordsSelectedSnapshotHashes() throws Exception {
    ApprovedReleaseMaterializer.MaterializedRelease release = activatedRelease();
    ReleaseProvenance provenance = writer.write(stagedPayload(release), release);

    ReleaseProvenance.SelectedPage page = provenance.selectedPages().stream()
        .filter(selected -> selected.publicId().equals("a"))
        .findFirst()
        .orElseThrow();
    ApprovedPageSnapshot snapshot = release.selectedSnapshots().stream()
        .filter(selected -> selected.publicId().equals("a"))
        .findFirst()
        .orElseThrow();
    assertEquals(snapshot.hashes().russianSha256(), page.ruSha256());
    assertEquals(snapshot.hashes().englishSha256(), page.enSha256());
    assertEquals(TreeHasher.sha256(PageReferenceMapCodec.write(snapshot.references())), page.referencesSha256());
  }

  @Test
  void recordsProjectionHashes() throws Exception {
    ApprovedReleaseMaterializer.MaterializedRelease release = activatedRelease();
    ReleaseProvenance provenance = writer.write(stagedPayload(release), release);

    ReleaseProvenance.SelectedPage page = provenance.selectedPages().stream()
        .filter(selected -> selected.publicId().equals("a"))
        .findFirst()
        .orElseThrow();
    assertEquals(TreeHasher.sha256(body(release.manifest().entries(), "a").getBytes(StandardCharsets.UTF_8)),
        page.ruProjectionSha256());
    assertEquals(TreeHasher.sha256(body(release.manifest().englishEntries(), "a").getBytes(StandardCharsets.UTF_8)),
        page.enProjectionSha256());
  }

  @Test
  void recordsActivationAndDeactivationCounts() throws Exception {
    ReleaseProvenance activated = writer.write(stagedPayload(activatedRelease()), activatedRelease());
    ApprovedReleaseMaterializer.MaterializedRelease deactivated = deactivatedRelease();
    ReleaseProvenance fallback = writer.write(stagedPayload(deactivated), deactivated);

    assertEquals(1, activated.activationCount());
    assertEquals(0, activated.deactivationCount());
    assertEquals(0, fallback.activationCount());
    assertEquals(2, fallback.deactivationCount());
  }

  @Test
  void rejectsTamperedContent() throws Exception {
    ApprovedReleaseMaterializer.MaterializedRelease release = activatedRelease();
    Path staged = stagedPayload(release);
    writer.write(staged, release);

    Files.writeString(staged.resolve("src/content/blog/ru/a.md"), "\nmanual change\n", StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    assertMismatch(() -> writer.verify(staged));
  }

  @Test
  void rejectsTamperedManifest() throws Exception {
    ApprovedReleaseMaterializer.MaterializedRelease release = activatedRelease();
    Path staged = stagedPayload(release);
    writer.write(staged, release);

    Files.writeString(staged.resolve(ReleaseProvenanceWriter.MANIFEST_RELATIVE), " ", StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    assertMismatch(() -> writer.verify(staged));
  }

  @Test
  void rejectsExtraManagedFiles() throws Exception {
    ApprovedReleaseMaterializer.MaterializedRelease release = activatedRelease();
    Path staged = stagedPayload(release);
    writer.write(staged, release);

    Files.writeString(staged.resolve("src/data/pages/ru/injected.json"), "{}\n", StandardCharsets.UTF_8);

    assertMismatch(() -> writer.verify(staged));
  }

  @Test
  void rejectsMissingManifest() throws Exception {
    ApprovedReleaseMaterializer.MaterializedRelease release = activatedRelease();
    Path staged = stagedPayload(release);

    assertMismatch(() -> writer.verify(staged));
  }

  private static void assertMismatch(ThrowingRunnable runnable) {
    ReleaseProvenanceWriter.ReleaseProvenanceException error = assertThrows(
        ReleaseProvenanceWriter.ReleaseProvenanceException.class,
        runnable::run);
    assertTrue(error.getMessage().contains("release-provenance-mismatch"));
  }

  private ApprovedReleaseMaterializer.MaterializedRelease activatedRelease() throws Exception {
    return materialize(List.of(
        snapshot(
            "A",
            "a",
            "vault-ref-a",
            "[B](ref:ref-0001)",
            "[B](ref:ref-0001)",
            List.of("ref-0001"),
            Map.of("ref-0001", new PageReferenceMap.Reference("vault-ref-b", "B", "", "B"))),
        snapshot("B", "b", "vault-ref-b", "B body", "B body", List.of(), Map.of())));
  }

  private ApprovedReleaseMaterializer.MaterializedRelease deactivatedRelease() throws Exception {
    return materialize(List.of(snapshot(
        "A",
        "a",
        "vault-ref-a",
        "[Draft](ref:ref-0001)",
        "[Draft](ref:ref-0001)",
        List.of("ref-0001"),
        Map.of("ref-0001", new PageReferenceMap.Reference("vault-ref-draft", "Draft", "", "Draft")))));
  }

  private ApprovedReleaseMaterializer.MaterializedRelease materialize(List<ApprovedPageSnapshot> snapshots)
      throws Exception {
    Path vault = Files.createDirectory(temp.resolve("vault-" + System.nanoTime()));
    for (ApprovedPageSnapshot snapshot : snapshots) {
      Path source = vault.resolve(snapshot.sourcePath());
      Files.createDirectories(source.getParent());
      Files.writeString(source, "source " + snapshot.publicId(), StandardCharsets.UTF_8);
    }
    return new ApprovedReleaseMaterializer().materialize(snapshots, vault);
  }

  private Path stagedPayload(ApprovedReleaseMaterializer.MaterializedRelease release) throws Exception {
    Path staged = Files.createDirectory(temp.resolve("staged-" + System.nanoTime()));
    for (String root : TreeHasher.MANAGED_ROOTS) {
      Files.createDirectories(staged.resolve(root));
    }
    for (ManifestEntry entry : release.manifest().entries()) {
      write(staged.resolve(entry.targetPath()), entry.body());
    }
    for (ManifestEntry entry : release.manifest().englishEntries()) {
      write(staged.resolve(entry.targetPath()), entry.body());
    }
    write(staged.resolve("src/data/pages/ru/search.json"), "{}\n");
    write(staged.resolve("src/data/pages/en/search.json"), "{}\n");
    return staged;
  }

  private static void write(Path path, String value) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, value, StandardCharsets.UTF_8);
  }

  private static String body(List<ManifestEntry> entries, String publicId) {
    return entries.stream()
        .filter(entry -> publicId.equals(entry.metadata().get("id")))
        .findFirst()
        .orElseThrow()
        .body();
  }

  private static ApprovedPageSnapshot snapshot(
      String title,
      String publicId,
      String pageRef,
      String ru,
      String en,
      List<String> order,
      Map<String, PageReferenceMap.Reference> references) {
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
        entry(publicId, "/ru/notes/" + publicId + "/", "src/content/blog/ru/" + publicId + ".md", ru),
        entry(publicId, "/en/notes/" + publicId + "/", "src/content/blog/en/" + publicId + ".md", en),
        map,
        new SnapshotHashes(map.ruSha256(), map.enSha256()));
  }

  private static ManifestEntry entry(String publicId, String route, String targetPath, String body) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", publicId);
    metadata.put("title", publicId);
    metadata.put("route", route);
    return new ManifestEntry("blog/" + publicId + ".md", targetPath, route, metadata, body);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
