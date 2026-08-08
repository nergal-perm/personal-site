package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RefreshPublicationQueueCliAcceptanceTest {

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
    void refreshReportsQueueRefreshedWithZeroCountsForAnEmptyVault() throws Exception {
        Path reviewRoot = vaultRoot.resolve("review");
        Files.createDirectories(reviewRoot);

        int exitCode = new CommandLine(new Main()).execute(
                "refresh-publication-queue",
                "--vault", vaultRoot.toString(),
                "--review", reviewRoot.toString(),
                "--json");

        assertEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertEquals("queue_refreshed", response.get("status").asText());
        assertEquals(0, response.get("updatedCount").asInt());
        assertEquals(0, response.get("unchangedCount").asInt());
        assertEquals(0, response.get("uncertainCount").asInt());
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
