package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.translation.NullTranslationWorker;
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

class PrepareCliAcceptanceTest {

    private static final Path SCHEMA_PATH = Path.of("../bridge-contract/schema-v2.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PublicationIdentity IDENTITY =
            PublicationIdentity.of("blog", "essay", "my-essay");

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
        int exitCode = prepare("../../etc/passwd.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("prepare", response.get("command").asText());
        assertFalse(response.get("ok").asBoolean());
        assertEquals("metadata_blocked", response.get("status").asText());
    }

    @Test
    void absentNoteProducesBlockedSchemaV2Response() throws Exception {
        int exitCode = prepare("blog/does-not-exist.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("Note was not found in the vault.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void notePathWithShellMetacharactersIsTreatedAsLiteralData() throws Exception {
        int exitCode = prepare("blog/note; touch pwned.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("Note was not found in the vault.",
                response.get("diagnostics").get(0).get("message").asText());
    }

    @Test
    void essayMissingSourceIdProducesBlockedSchemaV2Response() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/my-essay.md"), """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay""");

        int exitCode = prepare("blog/my-essay.md");

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertEquals("id", response.get("diagnostics").get(0).get("field").asText());
    }

    @Test
    void pluginPrepareArgvIncludingJobsInvokesPrepareCommand() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/my-essay.md"), """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: source-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay""");
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
        JsonNode response = soleJsonValueOnStdout();
        assertEquals("prepare", response.get("command").asText());
        assertEquals("ready_for_review", response.get("status").asText());
    }

    @Test
    void preparingChangedApprovedEssayProducesDiffAndNewCandidate() throws Exception {
        writeEssay("# My Essay\n\nChanged body.");
        Path reviewRoot = vaultRoot.resolve("review");
        installApproved(reviewRoot, "# My Essay\n\nApproved body.", "Old English candidate");
        installCandidate(reviewRoot, "# My Essay\n\nChanged body.", "Old English candidate");

        int exitCode = prepare("blog/my-essay.md",
                TranslationWorker.createNull("New translated candidate", "New EN title", "New EN description."));

        assertEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertTrue(response.get("ok").asBoolean());
        assertEquals("ready_for_review", response.get("status").asText());
        CandidateWorkspace candidates = CandidateWorkspace.create(reviewRoot);
        assertEquals("New translated candidate", candidates.read(IDENTITY).orElseThrow().enBody());
    }

    @Test
    void preparingWithOnlySerializationNoiseChangedInstallsNoNewCandidate() throws Exception {
        writeEssay("# My Essay\n\nApproved body.   ");
        Path reviewRoot = vaultRoot.resolve("review");
        installApproved(reviewRoot, "# My Essay\n\nApproved body.", "Prior English candidate");
        installCandidate(reviewRoot, "# My Essay\n\nApproved body.", "Prior English candidate");
        NullTranslationWorker worker = new NullTranslationWorker(
                dev.eugene.publicationexporter.translation.TranslationResult.success(
                        "Must not be installed", "Must not be installed", "Must not be installed"));

        int exitCode = prepare("blog/my-essay.md", worker);

        assertEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertTrue(response.get("ok").asBoolean());
        assertEquals("ready_for_review", response.get("status").asText());
        assertTrue(worker.requested().isEmpty());
        assertEquals("Prior English candidate",
                CandidateWorkspace.create(reviewRoot).read(IDENTITY).orElseThrow().enBody());
    }

    @Test
    void failedTranslationPreservesPriorCandidate() throws Exception {
        writeEssay("# My Essay\n\nChanged body.");
        Path reviewRoot = vaultRoot.resolve("review");
        installApproved(reviewRoot, "# My Essay\n\nApproved body.", "Prior English candidate");
        installCandidate(reviewRoot, "# My Essay\n\nChanged body.", "Prior English candidate");

        int exitCode = prepare("blog/my-essay.md", TranslationWorker.createNullFailing("worker crashed"));

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertFalse(response.get("ok").asBoolean());
        assertEquals("translation_failed", response.get("status").asText());
        assertEquals("Prior English candidate",
                CandidateWorkspace.create(reviewRoot).read(IDENTITY).orElseThrow().enBody());
    }

    @Test
    void invalidTranslationPreservesPriorCandidate() throws Exception {
        writeEssay("# My Essay\n\nChanged body.");
        Path reviewRoot = vaultRoot.resolve("review");
        installApproved(reviewRoot, "# My Essay\n\nApproved body.", "Prior English candidate");
        installCandidate(reviewRoot, "# My Essay\n\nChanged body.", "Prior English candidate");

        int exitCode = prepare("blog/my-essay.md",
                TranslationWorker.createNull("New English [route](/ru/route)", "New EN title", "New EN description."));

        assertNotEquals(0, exitCode);
        JsonNode response = soleJsonValueOnStdout();
        assertConformsToSchemaV2(response);
        assertFalse(response.get("ok").asBoolean());
        assertEquals("translation_failed", response.get("status").asText());
        assertTrue(response.get("diagnostics").get(0).get("message").asText().contains("/ru/"));
        assertEquals("Prior English candidate",
                CandidateWorkspace.create(reviewRoot).read(IDENTITY).orElseThrow().enBody());
    }

    private int prepare(String notePath) {
        return new CommandLine(new Main()).execute(
                "prepare",
                "--vault", vaultRoot.toString(),
                "--note", notePath,
                "--review", vaultRoot.resolve("review").toString(),
                "--jobs", vaultRoot.resolve(".publication-jobs").toString(),
                "--json");
    }

    private int prepare(String notePath, TranslationWorker translationWorker) {
        PrepareCommand prepareCommand = new PrepareCommand(translationWorker);
        CommandLine commandLine = new CommandLine(new Main(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == PrepareCommand.class) {
                    return cls.cast(prepareCommand);
                }
                return CommandLine.defaultFactory().create(cls);
            }
        });
        return commandLine.execute(
                "prepare",
                "--vault", vaultRoot.toString(),
                "--note", notePath,
                "--review", vaultRoot.resolve("review").toString(),
                "--jobs", vaultRoot.resolve(".publication-jobs").toString(),
                "--json");
    }

    private void writeEssay(String body) throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/my-essay.md"), """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: source-my-essay
                title: My Essay
                description: A valid description.
                ---
                """ + body);
    }

    private void installApproved(Path reviewRoot, String ruBody, String enBody) {
        ApprovedSnapshotWorkspace.create(reviewRoot).install(IDENTITY, ruBody, enBody,
                "My Essay", "EN title", "A valid description.", "EN description.",
                referenceMap(ruBody, enBody));
    }

    private void installCandidate(Path reviewRoot, String ruBody, String enBody) {
        CandidateWorkspace.create(reviewRoot).install(IDENTITY, ruBody, enBody,
                "My Essay", "EN title", "A valid description.", "EN description.",
                referenceMap(ruBody, enBody));
    }

    private ReferenceMap referenceMap(String ruBody, String enBody) {
        return ReferenceMap.empty(IDENTITY,
                ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody),
                ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("EN title"),
                ContentHash.sha256Hex("A valid description."), ContentHash.sha256Hex("EN description."));
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

    private void assertConformsToSchemaV2(JsonNode response) throws Exception {
        Set<ValidationMessage> errors = schemaV2().validate(response);
        assertTrue(errors.isEmpty(), () -> "Schema violations: " + errors);
    }

    private JsonSchema schemaV2() throws Exception {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                .getSchema(Files.newInputStream(SCHEMA_PATH));
    }
}
