package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class InspectPublicationCliAcceptanceTest {

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
        int exitCode = new CommandLine(new Main()).execute(
                "inspect-publication",
                "--vault", vaultRoot.toString(),
                "--note", "../../etc/passwd.md",
                "--review", vaultRoot.resolve("review").toString(),
                "--json");

        assertNotEquals(0, exitCode);
        JsonNode response = new ObjectMapper()
                .readTree(capturedOut.toString(StandardCharsets.UTF_8));
        assertEquals(2, response.get("schemaVersion").asInt());
        assertEquals("inspect-publication", response.get("command").asText());
        assertFalse(response.get("ok").asBoolean());
        assertEquals("metadata_blocked", response.get("status").asText());
        assertEquals("Note path escapes the vault root.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void absentNoteProducesBlockedSchemaV2Response() throws Exception {
        int exitCode = new CommandLine(new Main()).execute(
                "inspect-publication",
                "--vault", vaultRoot.toString(),
                "--note", "blog/does-not-exist.md",
                "--review", vaultRoot.resolve("review").toString(),
                "--json");

        assertNotEquals(0, exitCode);
        JsonNode response = new ObjectMapper()
                .readTree(capturedOut.toString(StandardCharsets.UTF_8));
        assertFalse(response.get("ok").asBoolean());
        assertEquals("Note was not found in the vault.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void notePathWithShellMetacharactersIsTreatedAsLiteralData() throws Exception {
        int exitCode = new CommandLine(new Main()).execute(
                "inspect-publication",
                "--vault", vaultRoot.toString(),
                "--note", "blog/note; touch pwned.md",
                "--review", vaultRoot.resolve("review").toString(),
                "--json");

        assertNotEquals(0, exitCode);
        JsonNode response = new ObjectMapper()
                .readTree(capturedOut.toString(StandardCharsets.UTF_8));
        assertFalse(response.get("ok").asBoolean());
        assertEquals("Note was not found in the vault.",
                response.get("diagnostics").get(0).get("message").asText());
    }
}
