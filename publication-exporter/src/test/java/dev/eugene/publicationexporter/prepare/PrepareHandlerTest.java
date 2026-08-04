package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.translation.NullTranslationWorker;
import dev.eugene.publicationexporter.translation.TranslationResult;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.NoSuchElementException;

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
            ---
            # My Essay

            Plain prose body.""";

    @Test
    void validEssayInstallsOneCandidateAndReturnsReadyForReview() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(TranslationWorker.createNull("Translated body"), workspace);

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals("my-essay", response.identity().publicId());
        assertEquals(0, response.diagnostics().size());
        assertEquals(1, workspace.installed().size());
        NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);
        assertEquals("# My Essay\n\nPlain prose body.", installed.ruBody());
        assertEquals("Translated body", installed.enBody());
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
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(TranslationWorker.createNull("Translated body"), workspace);

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
                TranslationWorker.createNullFailing("worker crashed"), workspace);

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
                TranslationWorker.createNull(" \n\t"), workspace);

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
                ---
                # My Essay""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essayWithoutSourceId));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullTranslationWorker worker = new NullTranslationWorker(TranslationResult.success("EN"));
        PrepareHandler handler = new PrepareHandler(worker, workspace);

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("id", response.diagnostics().get(0).field());
        assertTrue(worker.requestedBodies().isEmpty());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void workerReceivesOnlyTheFrontmatterStrippedBody() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullTranslationWorker worker = new NullTranslationWorker(TranslationResult.success("EN"));
        PrepareHandler handler = new PrepareHandler(worker, CandidateWorkspace.createNull());

        handler.prepare(path, vaultReader);

        assertEquals(java.util.List.of("# My Essay\n\nPlain prose body."), worker.requestedBodies());
    }

    @Test
    void sameInputsBuiltTwiceProduceIdenticalCandidateBytes() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace firstWorkspace = new NullCandidateWorkspace();
        NullCandidateWorkspace secondWorkspace = new NullCandidateWorkspace();

        new PrepareHandler(TranslationWorker.createNull("Translated body"), firstWorkspace)
                .prepare(path, vaultReader);
        new PrepareHandler(TranslationWorker.createNull("Translated body"), secondWorkspace)
                .prepare(path, vaultReader);

        NullCandidateWorkspace.InstalledCandidate first = firstWorkspace.installed().get(0);
        NullCandidateWorkspace.InstalledCandidate second = secondWorkspace.installed().get(0);
        assertEquals(first.ruBody(), second.ruBody());
        assertEquals(first.enBody(), second.enBody());
        assertEquals(first.referenceMap().ruHash(), second.referenceMap().ruHash());
        assertEquals(first.referenceMap().enHash(), second.referenceMap().enHash());
    }

    @Test
    void unsafePathIsBlockedWithEscapeDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        PrepareHandler handler = new PrepareHandler(TranslationWorker.createNull("EN"), CandidateWorkspace.createNull());

        BridgeResponse response = handler.prepare(VaultRelativePath.of("../../etc/passwd.md"), vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Note path escapes the vault root.", response.diagnostics().get(0).message());
    }

    @Test
    void absentNoteIsBlockedWithNotFoundDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        PrepareHandler handler = new PrepareHandler(TranslationWorker.createNull("EN"), CandidateWorkspace.createNull());

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
        };
        PrepareHandler handler = new PrepareHandler(TranslationWorker.createNull("EN"), CandidateWorkspace.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("Note path must name a Markdown file.", response.diagnostics().get(0).message());
    }

    @Test
    void vanishedNoteDuringReadIsReportedAsMissing() {
        PrepareHandler handler = new PrepareHandler(TranslationWorker.createNull("EN"), CandidateWorkspace.createNull());
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");

        BridgeResponse response = handler.prepare(path,
                failingReader(new NoSuchElementException("gone")));

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Note was not found in the vault.", response.diagnostics().get(0).message());
    }

    @Test
    void unreadableNoteDuringReadIsReportedAsMissing() {
        PrepareHandler handler = new PrepareHandler(TranslationWorker.createNull("EN"), CandidateWorkspace.createNull());
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
        TranslationWorker failingWorker = ruBody -> {
            throw new UncheckedIOException(new IOException("scratch directory unavailable"));
        };
        PrepareHandler handler = new PrepareHandler(failingWorker, workspace);

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertTrue(response.diagnostics().get(0).message().contains("scratch directory unavailable"));
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void candidateInstallIoFailureReturnsTranslationFailed() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        CandidateWorkspace failingWorkspace = (identity, ruBody, enBody, referenceMap) -> {
            throw new UncheckedIOException(new IOException("candidate disk unavailable"));
        };
        PrepareHandler handler = new PrepareHandler(
                TranslationWorker.createNull("Translated body"), failingWorkspace);

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
                TranslationWorker.createNull("Translated body"), CandidateWorkspace.create(reviewRoot));

        BridgeResponse response = handler.prepare(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertTrue(response.diagnostics().get(0).message().contains("escapes review root"));
        assertTrue(Files.notExists(outsideRoot.resolve("my-essay/candidate")));
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
        };
    }
}
