package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
import dev.eugene.publicationexporter.legacy.MigrationCatalogStore;
import dev.eugene.publicationexporter.legacy.MigrationJournalStore;
import dev.eugene.publicationexporter.legacy.SchemaActivationGuard;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyInventoryCliAcceptanceTest {

    private static final PublicationIdentity IDENTITY =
            PublicationIdentity.of("blog", "essay", "legacy-essay");
    private static final PublicationIdentity SECOND_IDENTITY =
            PublicationIdentity.of("blog", "essay", "second-legacy-essay");

    @TempDir
    Path reviewDirectory;

    private PrintStream originalOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() throws IOException {
        System.setOut(originalOut);
        Files.deleteIfExists(reviewDirectory.getParent().resolve("migration-draft.json"));
        Files.deleteIfExists(reviewDirectory.getParent().resolve("human-decision.json"));
        Files.deleteIfExists(reviewDirectory.getParent().resolve("review-alias"));
        Files.deleteIfExists(reviewDirectory.getParent().resolve("hard-linked-draft.json"));
        Files.deleteIfExists(reviewDirectory.getParent().resolve("apply-decision.json"));
    }

    @Test
    void approvedSnapshotWithoutRecordedSourceIdPrintsNamedBlocker() throws Exception {
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY, snapshotWithoutSourceId());

        int exitCode = new CommandLine(new Main()).execute(
                "legacy-inventory", "--review", reviewDirectory.toString());

        assertEquals(0, exitCode);
        JsonNode inventory = new ObjectMapper().readTree(capturedOut.toString(StandardCharsets.UTF_8));
        assertFalse(inventory.get("blockers").isEmpty());
        assertTrue(inventory.get("blockers").get(0).asText().contains(IDENTITY.toString()));
    }

    @Test
    void draftModeWritesOnlyOutsideReviewRootAndMarksTheFileNonExecutable() throws Exception {
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY, snapshotWithoutSourceId());
        Path draft = reviewDirectory.getParent().resolve("migration-draft.json");
        byte[] before = reviewTreeBytes(reviewDirectory);

        int exitCode = execute(
                "legacy-inventory", "--review", reviewDirectory.toString(), "--draft", draft.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(draft));
        assertTrue(new ObjectMapper().readTree(Files.readString(draft)).get("draftOnly").asBoolean());
        assertArrayEquals(before, reviewTreeBytes(reviewDirectory));
    }

    @Test
    void validationRejectsDraftWithoutReviewMutation() throws Exception {
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY, snapshotWithoutSourceId());
        Path draft = reviewDirectory.getParent().resolve("migration-draft.json");
        execute("legacy-inventory", "--review", reviewDirectory.toString(), "--draft", draft.toString());
        byte[] before = reviewTreeBytes(reviewDirectory);

        assertNotEquals(0, execute(
                "legacy-inventory", "--review", reviewDirectory.toString(), "--validate", draft.toString()));
        assertArrayEquals(before, reviewTreeBytes(reviewDirectory));
    }

    @Test
    void freshHumanDecisionValidatesAndStaleDecisionFailsWithoutReviewMutation() throws Exception {
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY, snapshotWithoutSourceId());
        Path draft = reviewDirectory.getParent().resolve("migration-draft.json");
        Path decision = reviewDirectory.getParent().resolve("human-decision.json");
        execute("legacy-inventory", "--review", reviewDirectory.toString(), "--draft", draft.toString());
        JsonNode decisionTemplate = new ObjectMapper().readTree(Files.readString(draft)).get("decisionTemplate");
        Files.writeString(decision, decisionTemplate.toString(), StandardCharsets.UTF_8);

        assertEquals(0, execute(
                "legacy-inventory", "--review", reviewDirectory.toString(), "--validate", decision.toString()));
        assertEquals("validated", new ObjectMapper()
                .readTree(capturedOut.toString(StandardCharsets.UTF_8)).get("status").asText());

        ApprovedSnapshotWorkspace.create(reviewDirectory).install(SECOND_IDENTITY, snapshotWithoutSourceId());
        byte[] before = reviewTreeBytes(reviewDirectory);

        assertNotEquals(0, execute(
                "legacy-inventory", "--review", reviewDirectory.toString(), "--validate", decision.toString()));
        assertArrayEquals(before, reviewTreeBytes(reviewDirectory));
    }

    @Test
    void draftDestinationInsideReviewRootIsRejectedBeforeWriting() throws Exception {
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY, snapshotWithoutSourceId());
        Path draft = reviewDirectory.resolve("migration-draft.json");
        byte[] before = reviewTreeBytes(reviewDirectory);

        assertNotEquals(0, execute(
                "legacy-inventory", "--review", reviewDirectory.toString(), "--draft", draft.toString()));
        assertFalse(Files.exists(draft));
        assertArrayEquals(before, reviewTreeBytes(reviewDirectory));
    }

    @Test
    void symlinkedReviewAliasCannotWriteDraftIntoReviewRoot() throws Exception {
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY, snapshotWithoutSourceId());
        Path reviewAlias = reviewDirectory.getParent().resolve("review-alias");
        Files.createSymbolicLink(reviewAlias, reviewDirectory);
        Path draft = reviewAlias.resolve("migration-draft.json");
        byte[] before = reviewTreeBytes(reviewDirectory);

        assertNotEquals(0, execute(
                "legacy-inventory", "--review", reviewDirectory.toString(), "--draft", draft.toString()));
        assertFalse(Files.exists(reviewDirectory.resolve("migration-draft.json")));
        assertArrayEquals(before, reviewTreeBytes(reviewDirectory));
    }

    @Test
    void hardLinkedDraftDestinationCannotTruncateReviewFile() throws Exception {
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY, snapshotWithoutSourceId());
        Path source = reviewDirectory.resolve("hard-link-source.json");
        Path draft = reviewDirectory.getParent().resolve("hard-linked-draft.json");
        Files.writeString(source, "review content", StandardCharsets.UTF_8);
        try {
            Files.createLink(draft, source);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false, "Hard links unavailable: " + exception.getMessage());
            return;
        }
        byte[] before = reviewTreeBytes(reviewDirectory);

        assertNotEquals(0, execute(
                "legacy-inventory", "--review", reviewDirectory.toString(), "--draft", draft.toString()));
        assertArrayEquals(before, reviewTreeBytes(reviewDirectory));
    }

    @Test
    void draftAndValidationOptionsAreMutuallyExclusive() throws Exception {
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY, snapshotWithoutSourceId());
        Path draft = reviewDirectory.getParent().resolve("migration-draft.json");
        Path decision = reviewDirectory.getParent().resolve("human-decision.json");
        byte[] before = reviewTreeBytes(reviewDirectory);

        assertNotEquals(0, execute(
                "legacy-inventory", "--review", reviewDirectory.toString(),
                "--draft", draft.toString(), "--validate", decision.toString()));
        assertFalse(Files.exists(draft));
        assertArrayEquals(before, reviewTreeBytes(reviewDirectory));
    }

    @Test
    void applyRequiresSeparateHumanDecisionAndActivatesTheRecordedGeneration() throws Exception {
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY, snapshotWithSourceId());
        Path draft = reviewDirectory.getParent().resolve("migration-draft.json");
        Path decision = reviewDirectory.getParent().resolve("apply-decision.json");
        execute("legacy-inventory", "--review", reviewDirectory.toString(), "--draft", draft.toString());
        JsonNode decisionTemplate = new ObjectMapper().readTree(Files.readString(draft))
                .get("decisionTemplate");
        Files.writeString(decision, decisionTemplate.toString(), StandardCharsets.UTF_8);

        assertEquals(0, execute("legacy-inventory", "--review", reviewDirectory.toString(),
                "--apply", decision.toString()));
        assertTrue(Files.exists(reviewDirectory.resolve(".migration/schema-v1.active.json")));
        assertTrue(SchemaActivationGuard.check(
                ApprovedSnapshotWorkspace.create(reviewDirectory),
                dev.eugene.publicationexporter.candidate.CandidateWorkspace.create(reviewDirectory),
                ActivationMarkerStore.create(reviewDirectory), MigrationJournalStore.create(reviewDirectory),
                MigrationCatalogStore.create(reviewDirectory)).isCurrent());
    }

    @Test
    void applyRejectsGeneratedDraftWithoutCreatingMigrationState() throws Exception {
        ApprovedSnapshotWorkspace.create(reviewDirectory).install(IDENTITY, snapshotWithSourceId());
        Path draft = reviewDirectory.getParent().resolve("migration-draft.json");
        execute("legacy-inventory", "--review", reviewDirectory.toString(), "--draft", draft.toString());

        assertNotEquals(0, execute("legacy-inventory", "--review", reviewDirectory.toString(),
                "--apply", draft.toString()));
        assertFalse(Files.exists(reviewDirectory.resolve(".migration/migration-journal.json")));
        assertFalse(Files.exists(reviewDirectory.resolve(".migration/migration-catalog.json")));
        assertFalse(Files.exists(reviewDirectory.resolve(".migration/schema-v1.active.json")));
    }

    @Test
    void applyAndRecoveryModesAreMutuallyExclusive() throws Exception {
        Path decision = reviewDirectory.getParent().resolve("apply-decision.json");
        Files.writeString(decision, "{}", StandardCharsets.UTF_8);

        assertNotEquals(0, execute("legacy-inventory", "--review", reviewDirectory.toString(),
                "--apply", decision.toString(), "--roll-forward"));
    }

    private int execute(String... arguments) {
        return new CommandLine(new Main()).execute(arguments);
    }

    private static byte[] reviewTreeBytes(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            String serialized = paths
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .sorted()
                    .map(path -> path + "\n"
                            + Base64.getEncoder().encodeToString(read(root.resolve(path))) + "\n")
                    .collect(Collectors.joining());
            return serialized.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read review fixture file " + path, exception);
        }
    }

    private static CandidateSnapshot snapshotWithoutSourceId() {
        String body = "Legacy body";
        String fields = PublicFieldsCodec.write(List.of());
        ReferenceMap referenceMap = ReferenceMap.empty(
                IDENTITY,
                ContentHash.sha256Hex(body), ContentHash.sha256Hex(body),
                ContentHash.sha256Hex(fields), ContentHash.sha256Hex(fields),
                ContentHash.sha256Hex(""));
        return CandidateSnapshot.of(body, body, List.of(), List.of(), "", referenceMap);
    }

    private static CandidateSnapshot snapshotWithSourceId() {
        String body = "Legacy body";
        String fields = PublicFieldsCodec.write(List.of());
        ReferenceMap referenceMap = ReferenceMap.of(
                IDENTITY, "legacy-source-id", ContentHash.sha256Hex(body), ContentHash.sha256Hex(body),
                ContentHash.sha256Hex(fields), ContentHash.sha256Hex(fields), ContentHash.sha256Hex(""), List.of());
        return CandidateSnapshot.of(body, body, List.of(), List.of(), "", referenceMap);
    }
}
