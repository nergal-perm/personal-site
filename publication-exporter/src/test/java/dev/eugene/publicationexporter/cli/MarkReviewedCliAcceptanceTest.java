package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkReviewedCliAcceptanceTest {

    private static final Path SCHEMA_PATH = Path.of("../bridge-contract/schema-v2.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path vaultRoot;

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
    void unsafeNotePathProducesBlockedSchemaV2Response() throws Exception {
        int exitCode = markReviewed("../../etc/passwd.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("mark-reviewed", response.get("command").asText());
        assertFalse(response.get("ok").asBoolean());
        assertEquals("metadata_blocked", response.get("status").asText());
    }

    @Test
    void noCandidateProducesBlockedSchemaV2Response() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/my-essay.md"), VALID_ESSAY);

        int exitCode = markReviewed("blog/my-essay.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("No candidate exists to approve.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void exactCandidateProducesApprovedSchemaV2ResponseAndInstallsTheApprovedSnapshot() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/my-essay.md"), VALID_ESSAY);
        Path reviewDirectory = vaultRoot.resolve("review");
        installExactCandidate(reviewDirectory);

        int exitCode = markReviewed("blog/my-essay.md");

        assertEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertTrue(response.get("ok").asBoolean());
        assertEquals("ready_to_publish", response.get("status").asText());
        assertEquals("my-essay", response.get("identity").get("publicId").asText());
        assertTrue(Files.exists(reviewDirectory.resolve("blog/my-essay/approved/ru.md")));
    }

    private void installExactCandidate(Path reviewDirectory) {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        String ruBody = "# My Essay";
        String enBody = "# My Essay (EN)";
        String ruHash = dev.eugene.publicationexporter.hash.ContentHash.sha256Hex(ruBody);
        String enHash = dev.eugene.publicationexporter.hash.ContentHash.sha256Hex(enBody);
        CandidateWorkspace.create(reviewDirectory)
                .install(identity, ruBody, enBody, ReferenceMap.empty(identity, ruHash, enHash));
    }

    private int markReviewed(String notePath) {
        return new CommandLine(new Main()).execute(
                "mark-reviewed",
                "--vault", vaultRoot.toString(),
                "--note", notePath,
                "--review", vaultRoot.resolve("review").toString(),
                "--jobs", vaultRoot.resolve(".publication-jobs").toString(),
                "--json");
    }

    private static final String VALID_ESSAY = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            ---
            # My Essay""";

    private JsonNode soleJsonValueOnStdout() throws Exception {
        String stdout = capturedOut.toString(StandardCharsets.UTF_8);
        try (JsonParser parser = MAPPER.createParser(stdout)) {
            JsonNode value = MAPPER.readTree(parser);
            assertNull(parser.nextToken(),
                    () -> "stdout must hold exactly one JSON value, got: " + stdout);
            return value;
        }
    }

    private void assertConformsToSchemaV2(JsonNode response) throws Exception {
        Set<ValidationMessage> errors = schemaV2().validate(response);
        assertTrue(errors.isEmpty(), () -> "Schema violations: " + errors);
    }

    private JsonSchema schemaV2() throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                .getSchema(Files.newInputStream(SCHEMA_PATH));
    }
}
