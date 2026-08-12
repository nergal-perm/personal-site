package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class WritePublicationContractCliAcceptanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void contractDescribesTheInstalledKinds() throws Exception {
        int exitCode = new CommandLine(new Main()).execute("write-publication-contract");

        assertEquals(0, exitCode);
        JsonNode contract = soleJsonValueOnStdout();
        assertEquals(1, contract.get("contractVersion").asInt());

        JsonNode kinds = contract.get("kinds");
        assertEquals(6, kinds.size());
        List<String> kindNames = new ArrayList<>();
        for (JsonNode kind : kinds) {
            kindNames.add(kind.get("collection").asText() + "/" + kind.get("contentType").asText());
        }
        assertEquals(List.of(
                "bibliography/book", "blog/claim", "blog/essay", "blog/note", "concepts/concept", "music/album"),
                kindNames);

        JsonNode bookKind = kindNamed(kinds, "book");
        assertEquals("bibliography", bookKind.get("collection").asText());
        assertEquals("book", bookKind.get("contentType").asText());
        assertTrue(bookKind.get("structuredBody").isEmpty());
        JsonNode optionalBookFields = bookKind.get("optionalFields");
        JsonNode blockedBookFields = bookKind.get("blockedFields");
        JsonNode requiredBookFields = bookKind.get("requiredFields");
        assertEquals(8, requiredBookFields.size());
        assertFieldNamed(requiredBookFields, "publish", field -> {
            assertEquals("BOOLEAN", field.get("type").asText());
            assertEquals("true", field.get("allowedValues").get(0).asText());
        });
        assertFieldNamed(requiredBookFields, "publicCollection", field ->
                assertEquals("bibliography", field.get("allowedValues").get(0).asText()));
        assertFieldNamed(requiredBookFields, "publicContentType", field ->
                assertEquals("book", field.get("allowedValues").get(0).asText()));
        assertFieldNamed(requiredBookFields, "publicId", field ->
                assertTrue(field.get("pattern").asText().length() > 0));
        assertFieldNamed(requiredBookFields, "id", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredBookFields, "title", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredBookFields, "description", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredBookFields, "authors", field -> {
            assertEquals("STRING_LIST", field.get("type").asText());
            assertTrue(field.get("nonBlank").asBoolean());
        });
        assertEquals(7, optionalBookFields.size());
        assertFieldNamed(optionalBookFields, "publication", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(optionalBookFields, "publicationDate", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(optionalBookFields, "start", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(optionalBookFields, "end", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(optionalBookFields, "readingStatus", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(optionalBookFields, "use", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(optionalBookFields, "boundary", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertEquals(1, blockedBookFields.size());
        assertEquals("selectedQuote", blockedBookFields.get(0).asText());

        JsonNode essayKind = kindNamed(kinds, "essay");
        assertEquals("blog", essayKind.get("collection").asText());
        assertEquals("essay", essayKind.get("contentType").asText());
        assertTrue(essayKind.get("structuredBody").isEmpty());

        JsonNode requiredFields = essayKind.get("requiredFields");
        assertEquals(7, requiredFields.size());
        assertFieldNamed(requiredFields, "publish", field -> {
            assertEquals("BOOLEAN", field.get("type").asText());
            assertEquals("true", field.get("allowedValues").get(0).asText());
        });
        assertFieldNamed(requiredFields, "publicCollection", field ->
                assertEquals("blog", field.get("allowedValues").get(0).asText()));
        assertFieldNamed(requiredFields, "publicContentType", field ->
                assertEquals("essay", field.get("allowedValues").get(0).asText()));
        assertFieldNamed(requiredFields, "publicId", field ->
                assertTrue(field.get("pattern").asText().length() > 0));
        assertFieldNamed(requiredFields, "id", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredFields, "title", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredFields, "description", field -> assertTrue(field.get("nonBlank").asBoolean()));

        JsonNode noteKind = kindNamed(kinds, "note");
        assertEquals("blog", noteKind.get("collection").asText());
        assertEquals("note", noteKind.get("contentType").asText());
        assertTrue(noteKind.get("structuredBody").isEmpty());

        JsonNode requiredNoteFields = noteKind.get("requiredFields");
        assertEquals(7, requiredNoteFields.size());
        assertFieldNamed(requiredNoteFields, "publish", field -> {
            assertEquals("BOOLEAN", field.get("type").asText());
            assertEquals("true", field.get("allowedValues").get(0).asText());
        });
        assertFieldNamed(requiredNoteFields, "publicCollection", field ->
                assertEquals("blog", field.get("allowedValues").get(0).asText()));
        assertFieldNamed(requiredNoteFields, "publicContentType", field ->
                assertEquals("note", field.get("allowedValues").get(0).asText()));
        assertFieldNamed(requiredNoteFields, "publicId", field ->
                assertTrue(field.get("pattern").asText().length() > 0));
        assertFieldNamed(requiredNoteFields, "id", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredNoteFields, "title", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredNoteFields, "description", field -> assertTrue(field.get("nonBlank").asBoolean()));

        JsonNode claimKind = kindNamed(kinds, "claim");
        assertEquals("blog", claimKind.get("collection").asText());
        assertEquals("claim", claimKind.get("contentType").asText());
        assertTrue(claimKind.get("structuredBody").isEmpty());
        JsonNode requiredClaimFields = claimKind.get("requiredFields");
        assertEquals(8, requiredClaimFields.size());
        assertFieldNamed(requiredClaimFields, "statement", field ->
                assertTrue(field.get("nonBlank").asBoolean()));

        JsonNode conceptKind = kindNamed(kinds, "concept");
        assertEquals("concepts", conceptKind.get("collection").asText());
        assertEquals("concept", conceptKind.get("contentType").asText());
        assertTrue(conceptKind.get("structuredBody").isEmpty());
        JsonNode requiredConceptFields = conceptKind.get("requiredFields");
        assertEquals(7, requiredConceptFields.size());
        List<String> requiredConceptFieldNames = new ArrayList<>();
        for (JsonNode field : requiredConceptFields) {
            requiredConceptFieldNames.add(field.get("name").asText());
        }
        assertEquals(List.of(
                "publish", "publicCollection", "publicContentType", "publicId", "id", "title", "description"),
                requiredConceptFieldNames);
        assertFieldNamed(requiredConceptFields, "publish", field -> {
            assertEquals("BOOLEAN", field.get("type").asText());
            assertEquals("true", field.get("allowedValues").get(0).asText());
        });
        assertFieldNamed(requiredConceptFields, "publicCollection", field ->
                assertEquals("concepts", field.get("allowedValues").get(0).asText()));
        assertFieldNamed(requiredConceptFields, "publicContentType", field ->
                assertEquals("concept", field.get("allowedValues").get(0).asText()));
        assertFieldNamed(requiredConceptFields, "publicId", field ->
                assertTrue(field.get("pattern").asText().length() > 0));
        assertFieldNamed(requiredConceptFields, "id", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredConceptFields, "title", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredConceptFields, "description", field ->
                assertTrue(field.get("nonBlank").asBoolean()));
        JsonNode optionalConceptFields = conceptKind.get("optionalFields");
        assertEquals(3, optionalConceptFields.size());
        List<String> optionalConceptFieldNames = new ArrayList<>();
        for (JsonNode field : optionalConceptFields) {
            optionalConceptFieldNames.add(field.get("name").asText());
        }
        assertEquals(List.of("notThis", "examples", "relations"), optionalConceptFieldNames);
        assertFieldNamed(optionalConceptFields, "notThis", field -> {
            assertEquals("STRING", field.get("type").asText());
            assertTrue(field.get("nonBlank").asBoolean());
        });
        assertFieldNamed(optionalConceptFields, "examples", field -> {
            assertEquals("STRING_LIST", field.get("type").asText());
            assertTrue(field.get("nonBlank").asBoolean());
        });
        assertFieldNamed(optionalConceptFields, "relations", field -> {
            assertEquals("STRUCTURED_LIST", field.get("type").asText());
            assertEquals(List.of("name", "relation"), jsonStrings(field.get("structuredMembers")));
        });

        JsonNode albumKind = kindNamed(kinds, "album");
        assertEquals("music", albumKind.get("collection").asText());
        assertEquals("album", albumKind.get("contentType").asText());
        assertTrue(albumKind.get("structuredBody").isEmpty());
        JsonNode requiredAlbumFields = albumKind.get("requiredFields");
        assertEquals(11, requiredAlbumFields.size());
        List<String> requiredAlbumFieldNames = new ArrayList<>();
        for (JsonNode field : requiredAlbumFields) {
            requiredAlbumFieldNames.add(field.get("name").asText());
        }
        assertEquals(List.of(
                "publish", "publicCollection", "publicContentType", "publicId", "id", "title",
                "description", "artist", "work", "context", "association"), requiredAlbumFieldNames);
        assertFieldNamed(requiredAlbumFields, "publish", field -> {
            assertEquals("BOOLEAN", field.get("type").asText());
            assertEquals("true", field.get("allowedValues").get(0).asText());
        });
        assertFieldNamed(requiredAlbumFields, "publicCollection", field ->
                assertEquals("music", field.get("allowedValues").get(0).asText()));
        assertFieldNamed(requiredAlbumFields, "publicContentType", field ->
                assertEquals("album", field.get("allowedValues").get(0).asText()));
        assertFieldNamed(requiredAlbumFields, "publicId", field ->
                assertTrue(field.get("pattern").asText().length() > 0));
        assertFieldNamed(requiredAlbumFields, "id", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredAlbumFields, "title", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredAlbumFields, "description", field ->
                assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredAlbumFields, "artist", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredAlbumFields, "work", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredAlbumFields, "context", field -> assertTrue(field.get("nonBlank").asBoolean()));
        assertFieldNamed(requiredAlbumFields, "association", field ->
                assertTrue(field.get("nonBlank").asBoolean()));

        JsonNode optionalAlbumFields = albumKind.get("optionalFields");
        assertEquals(7, optionalAlbumFields.size());
        List<String> optionalAlbumFieldNames = new ArrayList<>();
        for (JsonNode field : optionalAlbumFields) {
            optionalAlbumFieldNames.add(field.get("name").asText());
        }
        assertEquals(List.of(
                "format", "care", "releaseDate", "streamingUrl", "bandcampEmbedUrl", "listenFor", "genreTags"),
                optionalAlbumFieldNames);
        assertFieldNamed(optionalAlbumFields, "format", field -> {
            assertEquals("STRING", field.get("type").asText());
            assertTrue(field.get("nonBlank").asBoolean());
        });
        assertFieldNamed(optionalAlbumFields, "care", field -> {
            assertEquals("STRING", field.get("type").asText());
            assertTrue(field.get("nonBlank").asBoolean());
        });
        assertFieldNamed(optionalAlbumFields, "releaseDate", field -> {
            assertEquals("STRING", field.get("type").asText());
            assertTrue(field.get("nonBlank").asBoolean());
        });
        assertFieldNamed(optionalAlbumFields, "streamingUrl", field -> {
            assertEquals("STRING", field.get("type").asText());
            assertTrue(field.get("nonBlank").asBoolean());
        });
        assertFieldNamed(optionalAlbumFields, "bandcampEmbedUrl", field -> {
            assertEquals("STRING", field.get("type").asText());
            assertTrue(field.get("nonBlank").asBoolean());
        });
        assertFieldNamed(optionalAlbumFields, "listenFor", field -> {
            assertEquals("STRING_LIST", field.get("type").asText());
            assertTrue(field.get("nonBlank").asBoolean());
        });
        assertFieldNamed(optionalAlbumFields, "genreTags", field -> {
            assertEquals("STRING_LIST", field.get("type").asText());
            assertTrue(field.get("nonBlank").asBoolean());
        });
    }

    @Test
    void contractIsByteEquivalentAcrossTwoRequests() throws Exception {
        new CommandLine(new Main()).execute("write-publication-contract");
        String firstResponse = capturedOut.toString(StandardCharsets.UTF_8);
        capturedOut.reset();

        new CommandLine(new Main()).execute("write-publication-contract");
        String secondResponse = capturedOut.toString(StandardCharsets.UTF_8);

        assertEquals(firstResponse, secondResponse);
    }

    private void assertFieldNamed(JsonNode requiredFields, String name, Consumer<JsonNode> assertion) {
        for (JsonNode field : requiredFields) {
            if (field.get("name").asText().equals(name)) {
                assertion.accept(field);
                return;
            }
        }
        fail("No required field named " + name + " in " + requiredFields);
    }

    private JsonNode kindNamed(JsonNode kinds, String contentType) {
        for (JsonNode kind : kinds) {
            if (kind.get("contentType").asText().equals(contentType)) {
                return kind;
            }
        }
        fail("No kind named " + contentType + " in " + kinds);
        return null;
    }

    private List<String> jsonStrings(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            result.add(value.asText());
        }
        return result;
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
