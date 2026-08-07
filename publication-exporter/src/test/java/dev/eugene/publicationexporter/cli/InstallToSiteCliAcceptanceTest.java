package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.hash.ContentHash;
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
                ReferenceMap.empty(IDENTITY,
                        ContentHash.sha256Hex("# My Essay"), ContentHash.sha256Hex("# My Essay (EN)"),
                        ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("My Essay (EN)"),
                        ContentHash.sha256Hex("A valid description."),
                        ContentHash.sha256Hex("A valid description (EN).")));

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

    @Test
    void secondInstallToSiteReplacesTheManagedGeneration() throws Exception {
        Path reviewDirectory = workRoot.resolve("review");
        Path siteRoot = workRoot.resolve("site");
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
        approvedSnapshotWorkspace.install(IDENTITY,
                "# Old RU body", "# Old EN body", "Old RU title", "Old EN title",
                "Old RU description.", "Old EN description.",
                ReferenceMap.empty(IDENTITY,
                        ContentHash.sha256Hex("# Old RU body"), ContentHash.sha256Hex("# Old EN body"),
                        ContentHash.sha256Hex("Old RU title"), ContentHash.sha256Hex("Old EN title"),
                        ContentHash.sha256Hex("Old RU description."),
                        ContentHash.sha256Hex("Old EN description.")));

        int firstExitCode = installToSite(reviewDirectory, siteRoot);

        assertEquals(0, firstExitCode);
        String oldRuFile = Files.readString(siteRoot.resolve("src/content/blog/ru/my-essay.md"));
        String oldEnFile = Files.readString(siteRoot.resolve("src/content/blog/en/my-essay.md"));
        capturedOut.reset();
        approvedSnapshotWorkspace.install(IDENTITY,
                "# New RU body", "# New EN body", "New RU title", "New EN title",
                "New RU description.", "New EN description.",
                ReferenceMap.empty(IDENTITY,
                        ContentHash.sha256Hex("# New RU body"), ContentHash.sha256Hex("# New EN body"),
                        ContentHash.sha256Hex("New RU title"), ContentHash.sha256Hex("New EN title"),
                        ContentHash.sha256Hex("New RU description."),
                        ContentHash.sha256Hex("New EN description.")));

        int secondExitCode = installToSite(reviewDirectory, siteRoot);

        JsonNode result = soleJsonValueOnStdout();
        assertEquals(true, result.get("ok").asBoolean(),
                () -> "expected installed response, got: " + result);
        assertEquals(0, secondExitCode);
        assertTrue(Files.readString(siteRoot.resolve("src/content/blog/ru/my-essay.md")).contains("# New RU body"));
        assertTrue(Files.readString(siteRoot.resolve("src/content/blog/en/my-essay.md")).contains("# New EN body"));
        assertNotEquals(oldRuFile, Files.readString(siteRoot.resolve("src/content/blog/ru/my-essay.md")));
        assertNotEquals(oldEnFile, Files.readString(siteRoot.resolve("src/content/blog/en/my-essay.md")));
    }

    @Test
    void corruptedApprovedSnapshotProducesBlockedJsonInsteadOfEscaping() throws Exception {
        Path reviewDirectory = workRoot.resolve("review");
        Path siteRoot = workRoot.resolve("site");
        CorruptedApprovedSnapshotFixture.write(reviewDirectory, IDENTITY);

        int exitCode = installToSite(reviewDirectory, siteRoot);

        assertNotEquals(0, exitCode);
        JsonNode result = soleJsonValueOnStdout();
        assertEquals(false, result.get("ok").asBoolean());
        assertTrue(result.get("message").asText().contains("integrity validation failed"));
        assertTrue(Files.notExists(siteRoot));
    }

    @Test
    void managedSiteRecoveryFailureProducesBlockedJsonInsteadOfEscaping() throws Exception {
        Path reviewDirectory = workRoot.resolve("review");
        Path siteRoot = workRoot.resolve("site");
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY,
                "# My Essay", "# My Essay (EN)", "My Essay", "My Essay (EN)",
                "A valid description.", "A valid description (EN).",
                ReferenceMap.empty(IDENTITY,
                        ContentHash.sha256Hex("# My Essay"), ContentHash.sha256Hex("# My Essay (EN)"),
                        ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("My Essay (EN)"),
                        ContentHash.sha256Hex("A valid description."),
                        ContentHash.sha256Hex("A valid description (EN).")));
        assertEquals(0, installToSite(reviewDirectory, siteRoot));
        capturedOut.reset();
        Files.writeString(siteRoot.resolve("src/content/blog/ru/my-essay.md"), "tampered managed content");

        int exitCode = installToSite(reviewDirectory, siteRoot);

        assertNotEquals(0, exitCode);
        JsonNode result = soleJsonValueOnStdout();
        assertEquals(false, result.get("ok").asBoolean());
        assertTrue(result.get("message").asText().contains("Managed site recovery cannot continue"));
        assertTrue(result.get("message").asText().contains("provenance does not match the current managed tree"));
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
