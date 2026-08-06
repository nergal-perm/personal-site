package dev.eugene.publicationexporter.site;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteReleaseManifestTest {

    @TempDir
    Path root;

    @Test
    void managedTreesAreHashedOverKindLengthPathAndPayload() throws Exception {
        Path contentDir = root.resolve("src/content");
        Files.createDirectories(contentDir.resolve("blog/ru"));
        Files.writeString(contentDir.resolve("blog/ru/my-essay.md"), "---\nid: my-essay\n---\nBody.", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("src/data/pages"));
        Files.createDirectories(root.resolve("public/assets/vault"));

        SiteReleaseManifest manifest = SiteReleaseManifest.computeOver(
                root, List.of("public/assets/vault", "src/content", "src/data/pages"));

        assertEquals(1, manifest.schemaVersion());
        assertEquals(List.of(), manifest.selectedPages());
        assertEquals(0, manifest.activationCount());
        assertEquals(0, manifest.deactivationCount());
        assertEquals(3, manifest.managedTrees().size());
        assertEquals("src/content", manifest.managedTrees().get(1).relative());
        assertTrue(manifest.managedTrees().get(1).sha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void payloadDigestIsDeterministicAcrossRecomputation() throws Exception {
        Files.createDirectories(root.resolve("src/content/blog/ru"));
        Files.writeString(root.resolve("src/content/blog/ru/a.md"), "content", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("src/data/pages"));
        Files.createDirectories(root.resolve("public/assets/vault"));
        List<String> payloadRoots = List.of("public/assets/vault", "src/content", "src/data/pages");

        SiteReleaseManifest first = SiteReleaseManifest.computeOver(root, payloadRoots);
        SiteReleaseManifest second = SiteReleaseManifest.computeOver(root, payloadRoots);

        assertEquals(first.payloadDigest(), second.payloadDigest());
    }

    @Test
    void canonicalJsonOrdersFieldsToMatchCheckContentMjs() throws Exception {
        Files.createDirectories(root.resolve("src/content"));
        Files.createDirectories(root.resolve("src/data/pages"));
        Files.createDirectories(root.resolve("public/assets/vault"));

        SiteReleaseManifest manifest = SiteReleaseManifest.computeOver(
                root, List.of("public/assets/vault", "src/content", "src/data/pages"));

        String json = manifest.toCanonicalJson();
        int schemaVersionIndex = json.indexOf("\"schemaVersion\"");
        int selectedPagesIndex = json.indexOf("\"selectedPages\"");
        int managedTreesIndex = json.indexOf("\"managedTrees\"");
        int managedFilesIndex = json.indexOf("\"managedFiles\"");
        int activationCountIndex = json.indexOf("\"activationCount\"");
        int deactivationCountIndex = json.indexOf("\"deactivationCount\"");
        int payloadDigestIndex = json.indexOf("\"payloadDigest\"");
        assertTrue(schemaVersionIndex < selectedPagesIndex);
        assertTrue(selectedPagesIndex < managedTreesIndex);
        assertTrue(managedTreesIndex < managedFilesIndex);
        assertTrue(managedFilesIndex < activationCountIndex);
        assertTrue(activationCountIndex < deactivationCountIndex);
        assertTrue(deactivationCountIndex < payloadDigestIndex);
    }

    @Test
    void canonicalJsonEscapesQuoteInManagedTreePath() throws Exception {
        String payloadRoot = "tree\"quote";
        Files.createDirectories(root.resolve(payloadRoot));

        String json = SiteReleaseManifest.computeOver(root, List.of(payloadRoot)).toCanonicalJson();

        assertTrue(json.contains("\"relative\":\"tree\\\"quote\""));
    }

    @Test
    void canonicalJsonEscapesBackslashInManagedFilePath() throws Exception {
        String payloadRoot = "tree\\slash";
        Files.createDirectories(root.resolve(payloadRoot));
        Files.writeString(root.resolve(payloadRoot).resolve("item.txt"), "content", StandardCharsets.UTF_8);

        String json = SiteReleaseManifest.computeOver(root, List.of(payloadRoot)).toCanonicalJson();

        assertTrue(json.contains("\"relative\":\"tree\\\\slash\""));
        assertTrue(json.contains("\"path\":\"tree\\\\slash/item.txt\""));
    }

    @Test
    void managedTreeRejectsSymbolicLinkWithItsRelativePath() throws Exception {
        Path payloadRoot = root.resolve("payload");
        Files.createDirectories(payloadRoot);
        Path target = root.resolve("target.txt");
        Files.writeString(target, "target", StandardCharsets.UTF_8);
        Files.createSymbolicLink(payloadRoot.resolve("link-to-file"), target);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SiteReleaseManifest.computeOver(root, List.of("payload")));

        assertTrue(error.getMessage().contains("managed tree contains a symlink: link-to-file"));
    }

    @Test
    void fixedSingleFileFixtureMatchesKnownHashVector() throws Exception {
        Path payloadRoot = root.resolve("payload");
        Files.createDirectories(payloadRoot);
        Files.writeString(payloadRoot.resolve("a.txt"), "x", StandardCharsets.UTF_8);

        SiteReleaseManifest manifest = SiteReleaseManifest.computeOver(root, List.of("payload"));

        assertEquals("1d1ef41daa3c6e6160d7f4c4b893fabf37c426ad6cadbbabff930de35486bb13",
                manifest.managedTrees().get(0).sha256());
        assertEquals("59e2006b180b7a30dede79ddec73ff35cdf8b14fab62b9b7f46813d9c91093d6",
                manifest.payloadDigest());
    }
}
