package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallToSiteCliAcceptanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @TempDir
    Path workRoot;

    private PrintStream originalOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void noApprovedSnapshotProducesBlockedResultAndWritesNothing() throws Exception {
        Path siteRoot = workRoot.resolve("site");

        int exitCode = installToSite(workRoot.resolve("review"), siteRoot);

        assertNotEquals(0, exitCode);
        JsonNode result = soleJsonValueOnStdout();
        assertEquals(false, result.get("ok").asBoolean());
        assertEquals("No approved snapshot exists to install.", result.get("message").asText());
        assertTrue(Files.notExists(siteRoot));
    }

    @Test
    void approvedSnapshotIsInstalledIntoPreviouslyAbsentManagedRoots() throws Exception {
        Path reviewDirectory = workRoot.resolve("review");
        Path siteRoot = workRoot.resolve("site");
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY,
                "# My Essay", "# My Essay (EN)", "My Essay", "My Essay (EN)",
                "A valid description.", "A valid description (EN).",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        int exitCode = installToSite(reviewDirectory, siteRoot);

        assertEquals(0, exitCode);
        JsonNode result = soleJsonValueOnStdout();
        assertEquals(true, result.get("ok").asBoolean());
        assertEquals("my-essay", result.get("identity").get("publicId").asText());
        assertTrue(Files.exists(siteRoot.resolve("src/content/blog/ru/my-essay.md")));
        assertTrue(Files.exists(siteRoot.resolve("src/content/blog/en/my-essay.md")));
        assertTrue(Files.exists(siteRoot.resolve(".astro-export/release-provenance.json")));
        String manifest = Files.readString(siteRoot.resolve(".astro-export/release-provenance.json"));
        assertTrue(manifest.contains("\"schemaVersion\":1"));
    }

    private int installToSite(Path reviewDirectory, Path siteRoot) {
        return new CommandLine(new Main()).execute(
                "install-to-site",
                "--review", reviewDirectory.toString(),
                "--site", siteRoot.toString(),
                "--collection", "blog",
                "--content-type", "essay",
                "--id", "my-essay");
    }

    private JsonNode soleJsonValueOnStdout() throws Exception {
        String stdout = capturedOut.toString(StandardCharsets.UTF_8);
        try (JsonParser parser = MAPPER.createParser(stdout)) {
            JsonNode value = MAPPER.readTree(parser);
            assertNull(parser.nextToken(), () -> "stdout must hold exactly one JSON value, got: " + stdout);
            return value;
        }
    }
}
