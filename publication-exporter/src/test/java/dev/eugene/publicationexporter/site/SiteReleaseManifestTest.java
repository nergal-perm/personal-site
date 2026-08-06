package dev.eugene.publicationexporter.site;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
