package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.translation.NullTranslationWorker;
import dev.eugene.publicationexporter.translation.TranslationJob;
import dev.eugene.publicationexporter.translation.TranslationResult;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.NullWorkflowStatusEditor;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PrepareHandlerTest {

    @TempDir
    Path temporaryRoot;

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
            # My Essay

            Plain prose body.""";

    @Test
    void successfulPrepareWritesReadyForReviewWorkflowStatus() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, VALID_ESSAY));
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
                new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

        handler.prepare(path, vaultReader);

        assertEquals("ready_for_review", editor.currentValue(path, "workflowStatus"));
    }

    @Test
    void translationFailureWritesTranslationFailedWorkflowStatus() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, VALID_ESSAY));
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNullFailing("worker crashed"),
                new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

        handler.prepare(path, vaultReader);

        assertEquals("translation_failed", editor.currentValue(path, "workflowStatus"));
    }

    @Test
    void stalePrepareWritesStaleWorkflowStatus() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        AtomicInteger reads = new AtomicInteger();
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return reads.incrementAndGet() <= 1 ? VALID_ESSAY : essayWithBody("Changed while translating.");
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(
                Map.of(path, essayWithBody("Changed while translating.")));
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
                new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertEquals("stale", response.status());
        assertEquals(null, editor.currentValue(path, "workflowStatus"));
        assertEquals(2, reads.get());
    }

    @Test
    void blockedWorkflowStatusWriteDoesNotChangePrepareResponse() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(
                Map.of(path, essayWithBody("A different source.")));
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
                new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals(null, editor.currentValue(path, "workflowStatus"));
    }

    @Test
    void workflowStatusIoFailureDoesNotChangePrepareResponse() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        WorkflowStatusEditor editor = (notePath, expectedSourceHash, newValue) -> {
            throw new UncheckedIOException(new IOException("workflow status file unavailable"));
        };
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
                new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
    }

    @Test
    void validEssayInstallsOneCandidateAndReturnsReadyForReview() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals("my-essay", response.identity().publicId());
        assertEquals(0, response.diagnostics().size());
        assertEquals(1, workspace.installed().size());
        NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);
        assertEquals("# My Essay\n\nPlain prose body.", installed.ruBody());
        assertEquals("Translated body", installed.enBody());
        assertEquals("Translated title", installed.enTitle());
        assertEquals("Translated description.", installed.enDescription());
        assertEquals("My Essay", installed.ruTitle());
        assertEquals("A valid description.", installed.ruDescription());
        assertEquals(ContentHash.sha256Hex("# My Essay\n\nPlain prose body."),
                installed.referenceMap().ruHash());
        assertEquals(ContentHash.sha256Hex("Translated body"), installed.referenceMap().enHash());
        assertEquals(ContentHash.sha256Hex("My Essay"), installed.referenceMap().ruTitleHash());
        assertEquals(ContentHash.sha256Hex("Translated title"), installed.referenceMap().enTitleHash());
        assertEquals(ContentHash.sha256Hex("A valid description."),
                installed.referenceMap().ruDescriptionHash());
        assertEquals(ContentHash.sha256Hex("Translated description."),
                installed.referenceMap().enDescriptionHash());
        assertTrue(installed.referenceMap().occurrences().isEmpty());
    }

    @Test
    void unrelatedInvalidPublicationIsNotTouchedByPreparingTheValidOne() {
        VaultRelativePath validPath = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath invalidPath = VaultRelativePath.of("blog/broken.md");
        VaultReader validNoteReader = VaultReader.createNull(Map.of(validPath, VALID_ESSAY));
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                if (notePath.equals(invalidPath)) {
                    fail("Prepare must not query the unrelated invalid note.");
                }
                return validNoteReader.exists(notePath);
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                if (notePath.equals(invalidPath)) {
                    fail("Prepare must not read the unrelated invalid note.");
                }
                return validNoteReader.readSource(notePath);
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(validPath, vaultReader);

        assertTrue(response.ok());
        assertEquals(1, workspace.installed().size());
        assertEquals("my-essay", workspace.installed().get(0).identity().publicId());
    }

    @Test
    void translationFailureInstallsNoCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNullFailing("worker crashed"), workspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals(1, response.diagnostics().size());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void blankTranslationOutputInstallsNoCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull(" \n\t", "Translated title", "Translated description."), workspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void blankTranslatedTitleInstallsNoCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("Translated body", " \n\t", "Translated description."), workspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void blankTranslatedDescriptionInstallsNoCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("Translated body", "Translated title", " \n\t"), workspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void unadmittedNoteIsBlockedBeforeReachingTheWorker() {
        String essayWithoutSourceId = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essayWithoutSourceId));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationResult.success("EN", "EN title", "EN description."));
        PrepareHandler handler = new PrepareHandler(
                worker, workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("id", response.diagnostics().get(0).field());
        assertTrue(worker.requested().isEmpty());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void workerReceivesTheFrontmatterStrippedBodyPlusTitleAndDescription() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationResult.success("EN", "EN title", "EN description."));
        PrepareHandler handler = new PrepareHandler(worker, CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        handler.prepare(path, vaultReader);

        assertEquals(1, worker.requested().size());
        assertEquals("# My Essay\n\nPlain prose body.", worker.requested().get(0).ruBody());
        assertEquals("My Essay", worker.requested().get(0).ruTitle());
        assertEquals("A valid description.", worker.requested().get(0).ruDescription());
    }

    @Test
    void sameInputsBuiltTwiceProduceIdenticalCandidateBytes() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace firstWorkspace = new NullCandidateWorkspace();
        NullCandidateWorkspace secondWorkspace = new NullCandidateWorkspace();

        new PrepareHandler(TranslationWorker.createNull(
                "Translated body", "Translated title", "Translated description."), firstWorkspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull())
                .prepare(path, vaultReader);
        new PrepareHandler(TranslationWorker.createNull(
                "Translated body", "Translated title", "Translated description."), secondWorkspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull())
                .prepare(path, vaultReader);

        NullCandidateWorkspace.InstalledCandidate first = firstWorkspace.installed().get(0);
        NullCandidateWorkspace.InstalledCandidate second = secondWorkspace.installed().get(0);
        assertEquals(first.ruBody(), second.ruBody());
        assertEquals(first.enBody(), second.enBody());
        assertEquals(first.ruTitle(), second.ruTitle());
        assertEquals(first.enTitle(), second.enTitle());
        assertEquals(first.ruDescription(), second.ruDescription());
        assertEquals(first.enDescription(), second.enDescription());
        assertEquals(first.referenceMap().ruHash(), second.referenceMap().ruHash());
        assertEquals(first.referenceMap().enHash(), second.referenceMap().enHash());
    }

    @Test
    void unsafePathIsBlockedWithEscapeDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("EN", "EN title", "EN description."), CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(VaultRelativePath.of("../../etc/passwd.md"), vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Note path escapes the vault root.", response.diagnostics().get(0).message());
    }

    @Test
    void absentNoteIsBlockedWithNotFoundDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("EN", "EN title", "EN description."), CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(VaultRelativePath.of("blog/does-not-exist.md"), vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Note was not found in the vault.", response.diagnostics().get(0).message());
    }

    @Test
    void nonMarkdownPathIsBlockedBeforeReading() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.txt");
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return fail("Prepare must not read a non-Markdown path.");
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("EN", "EN title", "EN description."), CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("Note path must name a Markdown file.", response.diagnostics().get(0).message());
    }

    @Test
    void vanishedNoteDuringReadIsReportedAsMissing() {
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("EN", "EN title", "EN description."), CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");

        BridgeResponse response = handler.prepare(path,
                failingReader(new NoSuchElementException("gone")));

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Note was not found in the vault.", response.diagnostics().get(0).message());
    }

    @Test
    void unreadableNoteDuringReadIsReportedAsMissing() {
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("EN", "EN title", "EN description."), CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");

        BridgeResponse response = handler.prepare(path,
                failingReader(new UncheckedIOException(new IOException("unreadable"))));

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Note was not found in the vault.", response.diagnostics().get(0).message());
    }

    @Test
    void translationWorkerIoFailureReturnsTranslationFailed() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        TranslationWorker failingWorker = (job, ruBody, ruTitle, ruDescription) -> {
            throw new UncheckedIOException(new IOException("scratch directory unavailable"));
        };
        PrepareHandler handler = new PrepareHandler(
                failingWorker, workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertTrue(response.diagnostics().get(0).message().contains("scratch directory unavailable"));
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void competingPreparationsInstallOnlyTheFreshSource() throws Exception {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        AtomicReference<String> source = new AtomicReference<>(essayWithBody("First source body."));
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return source.get();
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        CountDownLatch firstTranslationStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstTranslation = new CountDownLatch(1);
        CountDownLatch releaseSecondTranslation = new CountDownLatch(1);
        AtomicInteger invocation = new AtomicInteger();
        java.util.List<TranslationJob> jobs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        TranslationWorker controlledWorker = (job, ruBody, ruTitle, ruDescription) -> {
            jobs.add(job);
            int currentInvocation = invocation.incrementAndGet();
            if (currentInvocation == 1) {
                firstTranslationStarted.countDown();
                await(releaseFirstTranslation);
                return TranslationResult.success("Stale English", "Stale title", "Stale description");
            }
            await(releaseSecondTranslation);
            return TranslationResult.success("Fresh English", "Fresh title", "Fresh description");
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler firstHandler = new PrepareHandler(
                controlledWorker, workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());
        PrepareHandler secondHandler = new PrepareHandler(
                controlledWorker, workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BridgeResponse> stalePreparation = executor.submit(() -> firstHandler.prepare(path, vaultReader));
            assertTrue(firstTranslationStarted.await(5, TimeUnit.SECONDS));
            source.set(essayWithBody("Second source body."));
            Future<BridgeResponse> freshPreparation = executor.submit(() -> secondHandler.prepare(path, vaultReader));

            releaseFirstTranslation.countDown();
            releaseSecondTranslation.countDown();

            assertEquals("stale", stalePreparation.get(5, TimeUnit.SECONDS).status());
            assertEquals("ready_for_review", freshPreparation.get(5, TimeUnit.SECONDS).status());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, jobs.size());
        assertFalse(jobs.get(0).sourceFingerprint().equals(jobs.get(1).sourceFingerprint()));
        assertEquals(1, workspace.installed().size());
        assertEquals("Second source body.", workspace.installed().get(0).ruBody());
        assertEquals("Fresh English", workspace.installed().get(0).enBody());
    }

    @Test
    void candidateInstallIoFailureReturnsTranslationFailed() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        CandidateWorkspace failingWorkspace = new CandidateWorkspace() {
            @Override
            public void install(PublicationIdentity identity, String ruBody, String enBody,
                    String ruTitle, String enTitle, String ruDescription, String enDescription,
                    ReferenceMap referenceMap) {
                throw new UncheckedIOException(new IOException("candidate disk unavailable"));
            }

            @Override
            public Optional<CandidatePaths> find(PublicationIdentity identity) {
                return Optional.empty();
            }

            @Override
            public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
                return Optional.empty();
            }
        };
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull(
                        "Translated body", "Translated title", "Translated description."), failingWorkspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertTrue(response.diagnostics().get(0).message().contains("candidate disk unavailable"));
    }

    @Test
    void candidateConfinementFailureReturnsTranslationFailedWithoutExternalWrite() throws Exception {
        Path reviewRoot = temporaryRoot.resolve("review");
        Path outsideRoot = temporaryRoot.resolve("outside");
        Files.createDirectories(reviewRoot);
        Files.createDirectories(outsideRoot);
        Files.createSymbolicLink(reviewRoot.resolve("blog"), outsideRoot);
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull(
                        "Translated body", "Translated title", "Translated description."),
                CandidateWorkspace.create(reviewRoot), ApprovedSnapshotWorkspace.createNull(),
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertTrue(response.diagnostics().get(0).message().contains("escapes review root"));
        assertTrue(Files.notExists(outsideRoot.resolve("my-essay/candidate")));
    }

    @Test
    void corruptApprovedSnapshotReturnsStructuredBlockedResponse() throws Exception {
        Path reviewRoot = temporaryRoot.resolve("corrupt-review");
        installApproved(reviewRoot);
        Files.writeString(reviewRoot.resolve("blog/my-essay/approved/references.json"), "not-json");
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("EN", "EN title", "EN description"),
                CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.create(reviewRoot),
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(
                VaultRelativePath.of("blog/my-essay.md"),
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/my-essay.md"), VALID_ESSAY)));

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("approved-snapshot", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("Approved snapshot lookup failed"));
    }

    @Test
    void escapingApprovedMemberReturnsStructuredBlockedResponse() throws Exception {
        Path reviewRoot = temporaryRoot.resolve("escaping-review");
        installApproved(reviewRoot);
        Path outside = Files.writeString(temporaryRoot.resolve("outside-ru.md"), "outside");
        Path ruPath = reviewRoot.resolve("blog/my-essay/approved/ru.md");
        Files.delete(ruPath);
        Files.createSymbolicLink(ruPath, outside);
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("EN", "EN title", "EN description"),
                CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.create(reviewRoot),
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(
                VaultRelativePath.of("blog/my-essay.md"),
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/my-essay.md"), VALID_ESSAY)));

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("approved-snapshot", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("escapes review root"));
    }

    private VaultReader failingReader(RuntimeException failure) {
        return new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                throw failure;
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for controlled translation completion");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Controlled translation was interrupted", interrupted);
        }
    }

    private static String essayWithBody(String body) {
        return """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                """ + body;
    }

    private void installApproved(Path reviewRoot) {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ApprovedSnapshotWorkspace.create(reviewRoot).install(
                identity, "# My Essay\n\nPlain prose body.", "EN body",
                "My Essay", "EN title", "A valid description.", "EN description",
                ReferenceMap.empty(identity,
                        ContentHash.sha256Hex("# My Essay\n\nPlain prose body."),
                        ContentHash.sha256Hex("EN body"),
                        ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("A valid description."),
                        ContentHash.sha256Hex("EN description")));
    }
}
