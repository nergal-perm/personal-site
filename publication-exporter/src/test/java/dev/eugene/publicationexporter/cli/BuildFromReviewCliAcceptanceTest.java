package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildFromReviewCliAcceptanceTest {

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
        Path outputRoot = workRoot.resolve("output");

        int exitCode = buildFromReview(workRoot.resolve("review"), outputRoot);

        assertNotEquals(0, exitCode);
        JsonNode result = soleJsonValueOnStdout();
        assertEquals(false, result.get("ok").asBoolean());
        assertEquals("No approved snapshot exists to release.", result.get("message").asText());
        assertTrue(Files.notExists(outputRoot));
    }

    @Test
    void legacyApprovedWorkspaceWithoutActivationMarkerIsBlockedByCli() throws Exception {
        Path reviewDirectory = workRoot.resolve("review");
        Path outputRoot = workRoot.resolve("output");
        installApprovedSnapshotWithoutActivationMarker(reviewDirectory, "# Legacy Essay", "# Legacy Essay (EN)");

        int exitCode = buildFromReview(reviewDirectory, outputRoot);

        assertNotEquals(0, exitCode);
        JsonNode result = soleJsonValueOnStdout();
        assertEquals(false, result.get("ok").asBoolean());
        assertTrue(result.get("message").asText().contains("read-only migration inventory"));
        assertTrue(Files.notExists(outputRoot));
    }

    @Test
    void approvedSnapshotProducesReleasedResultAndWritesBothEssayFilesPlusProvenance() throws Exception {
        Path reviewDirectory = workRoot.resolve("review");
        Path outputRoot = workRoot.resolve("output");
        String ruHash = installApprovedSnapshot(reviewDirectory, "# My Essay", "# My Essay (EN)");

        int exitCode = buildFromReview(reviewDirectory, outputRoot);

        assertEquals(0, exitCode);
        JsonNode result = soleJsonValueOnStdout();
        assertEquals(true, result.get("ok").asBoolean());
        assertEquals("my-essay", result.get("identity").get("publicId").asText());
        assertEquals(1, result.get("provenance").get("contractEdition").asInt());
        assertEquals(ruHash, result.get("provenance").get("approvedRuHash").asText());
        assertEquals(ruHash, result.get("provenance").get("outputRuHash").asText());
        assertTrue(result.get("message").isNull());

        Path releaseDir = outputRoot.resolve("blog").resolve("my-essay").resolve("release");
        assertEquals("# My Essay", Files.readString(releaseDir.resolve("ru.md")));
        assertEquals("# My Essay (EN)", Files.readString(releaseDir.resolve("en.md")));
        assertTrue(Files.readString(releaseDir.resolve("release-provenance.json")).contains("\"contractEdition\":1"));
    }

    @Test
    void corruptedApprovedSnapshotProducesBlockedJsonInsteadOfEscaping() throws Exception {
        Path reviewDirectory = workRoot.resolve("review");
        Path outputRoot = workRoot.resolve("output");
        CorruptedApprovedSnapshotFixture.write(reviewDirectory, IDENTITY);

        int exitCode = buildFromReview(reviewDirectory, outputRoot);

        assertNotEquals(0, exitCode);
        JsonNode result = soleJsonValueOnStdout();
        assertEquals(false, result.get("ok").asBoolean());
        assertTrue(result.get("message").asText().contains("migration inventory"));
        assertTrue(Files.notExists(outputRoot));
    }

    private String installApprovedSnapshot(Path reviewDirectory, String ruBody, String enBody) {
        String ruHash = ContentHash.sha256Hex(ruBody);
        List<PublicField> ruFields = List.of(
                PublicField.of("title", "RU title"), PublicField.of("description", "RU description"));
        List<PublicField> enFields = List.of(
                PublicField.of("title", "EN title"), PublicField.of("description", "EN description"));
        ReferenceMap referenceMap = ReferenceMap.of(IDENTITY, "source-my-essay",
                ruHash, ContentHash.sha256Hex(enBody),
                ContentHash.sha256Hex(PublicFieldsCodec.write(ruFields)),
                ContentHash.sha256Hex(PublicFieldsCodec.write(enFields)),
                ContentHash.sha256Hex(""), List.of(), ContentHash.sha256Hex("source body"));
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY,
                CandidateSnapshot.of(ruBody, enBody, ruFields, enFields, "", referenceMap));
        return ruHash;
    }

    private String installApprovedSnapshotWithoutActivationMarker(
            Path reviewDirectory, String ruBody, String enBody) {
        String ruHash = ContentHash.sha256Hex(ruBody);
        String enHash = ContentHash.sha256Hex(enBody);
        ApprovedSnapshotWorkspace.create(reviewDirectory)
                .install(IDENTITY, ruBody, enBody, "RU title", "EN title",
                        "RU description", "EN description", ReferenceMap.empty(
                                IDENTITY, ruHash, enHash,
                                ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                                ContentHash.sha256Hex("RU description"),
                                ContentHash.sha256Hex("EN description")));
        return ruHash;
    }

    private int buildFromReview(Path reviewDirectory, Path outputRoot) {
        return new CommandLine(new Main()).execute(
                "build-from-review",
                "--review", reviewDirectory.toString(),
                "--output", outputRoot.toString(),
                "--collection", "blog",
                "--content-type", "essay",
                "--id", "my-essay");
    }

    private JsonNode soleJsonValueOnStdout() throws Exception {
        String stdout = capturedOut.toString(StandardCharsets.UTF_8);
        try (JsonParser parser = MAPPER.createParser(stdout)) {
            JsonNode value = MAPPER.readTree(parser);
            assertNull(parser.nextToken(),
                    () -> "stdout must hold exactly one JSON value, got: " + stdout);
            return value;
        }
    }
}
