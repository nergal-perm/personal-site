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

class WritePublicationManifestCliAcceptanceTest {

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
    void manifestListsAllSelectedNotesWhenEveryOneAdmits() throws Exception {
        writeNote("blog/first-essay.md", """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: first-essay
                id: f1
                title: First Essay
                description: The first valid essay.
                ---
                """);
        writeNote("blog/second-essay.md", """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: second-essay
                id: s1
                title: Second Essay
                description: The second valid essay.
                ---
                """);

        int exitCode = new CommandLine(new Main()).execute(
                "write-publication-manifest", "--vault", vaultRoot.toString());

        assertEquals(0, exitCode);
        JsonNode manifest = soleJsonValueOnStdout();
        assertEquals(true, manifest.get("ok").asBoolean());
        assertEquals(2, manifest.get("entries").size());
        assertEquals("blog/first-essay.md", manifest.get("entries").get(0).get("path").asText());
        assertEquals("blog/second-essay.md", manifest.get("entries").get(1).get("path").asText());
    }

    @Test
    void manifestReportsNotOkWhenASelectedNoteIsInvalid() throws Exception {
        writeNote("blog/broken-essay.md", """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: broken-essay
                id: b1
                description: Missing its title.
                ---
                """);

        int exitCode = new CommandLine(new Main()).execute(
                "write-publication-manifest", "--vault", vaultRoot.toString());

        assertEquals(1, exitCode);
        JsonNode manifest = soleJsonValueOnStdout();
        assertEquals(false, manifest.get("ok").asBoolean());
        assertEquals(1, manifest.get("entries").size());
        JsonNode entry = manifest.get("entries").get(0);
        assertEquals("blog/broken-essay.md", entry.get("path").asText());
        assertNull(entry.get("identity"));
        assertEquals("title", entry.get("diagnostics").get(0).get("field").asText());
    }

    private void writeNote(String relativePath, String source) throws Exception {
        Path note = vaultRoot.resolve(relativePath);
        Files.createDirectories(note.getParent());
        Files.writeString(note, source, StandardCharsets.UTF_8);
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
