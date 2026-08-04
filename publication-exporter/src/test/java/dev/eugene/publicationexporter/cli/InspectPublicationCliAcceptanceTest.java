package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
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

class InspectPublicationCliAcceptanceTest {

    private static final Path SCHEMA_PATH = Path.of("../bridge-contract/schema-v2.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path vaultRoot;

    @TempDir
    Path outsideVaultRoot;

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
        int exitCode = inspect("../../etc/passwd.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals(2, response.get("schemaVersion").asInt());
        assertEquals("inspect-publication", response.get("command").asText());
        assertFalse(response.get("ok").asBoolean());
        assertEquals("metadata_blocked", response.get("status").asText());
        assertEquals("Note path escapes the vault root.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void absentNoteProducesBlockedSchemaV2Response() throws Exception {
        int exitCode = inspect("blog/does-not-exist.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertFalse(response.get("ok").asBoolean());
        assertEquals("Note was not found in the vault.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void notePathWithShellMetacharactersIsTreatedAsLiteralData() throws Exception {
        int exitCode = inspect("blog/note; touch pwned.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertFalse(response.get("ok").asBoolean());
        assertEquals("Note was not found in the vault.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void symlinkEscapingTheVaultIsReportedAsAbsentRatherThanRead() throws Exception {
        Path secret = Files.writeString(
                outsideVaultRoot.resolve("secret.md"), "# Outside the vault");
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.createSymbolicLink(vaultRoot.resolve("blog/link.md"), secret);

        int exitCode = inspect("blog/link.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertFalse(response.get("ok").asBoolean());
        assertEquals("metadata_blocked", response.get("status").asText());
        assertEquals("Note was not found in the vault.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void driveQualifiedNotePathIsBlockedAsAVaultEscape() throws Exception {
        int exitCode = inspect("C:/etc/passwd.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("Note path escapes the vault root.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    private int inspect(String notePath) {
        return new CommandLine(new Main()).execute(
                "inspect-publication",
                "--vault", vaultRoot.toString(),
                "--note", notePath,
                "--review", vaultRoot.resolve("review").toString(),
                "--json");
    }

    /**
     * Reads stdout as exactly one JSON value and fails if anything but whitespace follows it, so
     * stray logging or a second response on the same stream cannot slip past the contract.
     */
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
