package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.eugene.publicationexporter.note.Frontmatter;
import dev.eugene.publicationexporter.translation.TranslationWorker;
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
        prepare();

        int exitCode = markReviewed("blog/my-essay.md");

        assertEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertTrue(response.get("ok").asBoolean());
        assertEquals("ready_to_publish", response.get("status").asText());
        assertEquals("my-essay", response.get("identity").get("publicId").asText());
        assertTrue(Files.exists(reviewDirectory.resolve("blog/my-essay/approved/ru.md")));
        assertEquals("ready_to_publish", Frontmatter.parse(
                Files.readString(vaultRoot.resolve("blog/my-essay.md")))
                .string("workflowStatus").orElse(null));
    }

    @Test
    void secondMarkReviewedReplacesTheApprovedSnapshot() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Path note = vaultRoot.resolve("blog/my-essay.md");
        Files.writeString(note, VALID_ESSAY);
        Path approvedDirectory = vaultRoot.resolve("review/blog/my-essay/approved");

        prepare();
        assertEquals(0, markReviewed("blog/my-essay.md"));

        Files.writeString(note, """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: New Essay
                description: A new description.
                ---
                # New Essay

                New body.""");
        prepare();

        int exitCode = markReviewed("blog/my-essay.md");

        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("ready_to_publish", response.get("status").asText(),
                () -> "second mark-reviewed response: " + response);
        assertEquals(0, exitCode);
        assertEquals("# New Essay\n\nNew body.", Files.readString(approvedDirectory.resolve("ru.md")));
        assertEquals("# My Essay in English", Files.readString(approvedDirectory.resolve("en.md")));
        assertEquals("New Essay", Files.readString(approvedDirectory.resolve("ru.title")));
        assertEquals("A new description.", Files.readString(approvedDirectory.resolve("ru.description")));
    }

    @Test
    void corruptedApprovedSnapshotProducesBlockedSchemaV2Response() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/my-essay.md"), VALID_ESSAY);
        prepare();
        CorruptedApprovedSnapshotFixture.write(
                vaultRoot.resolve("review"),
                dev.eugene.publicationexporter.bridge.PublicationIdentity.of("blog", "essay", "my-essay"));

        int exitCode = markReviewed("blog/my-essay.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertFalse(response.get("ok").asBoolean());
        assertEquals("metadata_blocked", response.get("status").asText());
        assertEquals("approved-snapshot", response.get("diagnostics").get(0).get("field").asText());
        assertTrue(response.get("diagnostics").get(0).get("message").asText()
                .contains("integrity validation failed"));
    }

    private void prepare() throws Exception {
        PrepareCommand prepareCommand = new PrepareCommand(
                TranslationWorker.createNull("# My Essay in English", "EN title", "EN description"));
        CommandLine commandLine = new CommandLine(new Main(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == PrepareCommand.class) {
                    return cls.cast(prepareCommand);
                }
                return CommandLine.defaultFactory().create(cls);
            }
        });

        int exitCode = commandLine.execute(
                "prepare",
                "--vault", vaultRoot.toString(),
                "--note", "blog/my-essay.md",
                "--review", vaultRoot.resolve("review").toString(),
                "--jobs", vaultRoot.resolve(".publication-jobs").toString(),
                "--json");

        assertEquals(0, exitCode);
        capturedOut.reset();
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
            title: My Essay
            description: A valid description.
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
