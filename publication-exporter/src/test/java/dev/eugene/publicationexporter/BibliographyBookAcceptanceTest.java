package dev.eugene.publicationexporter;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandler;
import dev.eugene.publicationexporter.buildfromreview.ReleaseResult;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.legacy.ActivationMarkerTestFixtures;
import dev.eugene.publicationexporter.markreviewed.MarkReviewedHandler;
import dev.eugene.publicationexporter.prepare.PrepareHandler;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.site.FilesystemManagedSiteInstaller;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultAssetReader;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.NullWorkflowStatusEditor;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BibliographyBookAcceptanceTest {

    private static final String VALID_BOOK = """
            ---
            publish: true
            publicCollection: bibliography
            publicContentType: book
            publicId: the-lean-startup
            id: 8f2c-the-lean-startup
            title: The Lean Startup
            description: A valid description.
            authors:
              - Eric Ries
            publication: Crown Business
            publicationDate: 2011-09-13
            readingStatus: finished
            use: Explains how to test demand before scaling a product bet.
            boundary: Only the startup-method parts are directly relevant.
            ---
            # The Lean Startup

            A reading note body.""";

    @TempDir
    Path siteRoot;

    @Test
    void bibliographyBookCompletesAdmissionThroughSiteInstallation() throws Exception {
        VaultRelativePath path = VaultRelativePath.of("bibliography/the-lean-startup.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_BOOK));
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull();
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        TranslationWorker translationWorker = TranslationWorker.createNull(
                "Translated book body.",
                List.of(
                        PublicField.of("title", "The Lean Startup"),
                        PublicField.of("description", "A valid English description."),
                        PublicField.of("use", "Explains how to test demand before scaling a product bet."),
                        PublicField.of("boundary", "Only the startup-method parts are directly relevant.")));
        WorkflowStatusEditor workflowStatusEditor = new NullWorkflowStatusEditor(Map.of(path, VALID_BOOK));
        PrepareHandler prepareHandler = new PrepareHandler(
                noteIntake,
                translationWorker,
                candidateWorkspace,
                approvedSnapshotWorkspace,
                WorkflowStatusEditor.createNull());

        BridgeResponse prepareResponse = prepareHandler.prepare(path, vaultReader, vaultAssetReader);

        assertTrue(prepareResponse.ok(), prepareResponse.diagnostics().toString());
        PublicationIdentity identity = PublicationIdentity.of("bibliography", "book", "the-lean-startup");
        assertEquals(identity, prepareResponse.identity());

        MarkReviewedHandler markReviewedHandler = new MarkReviewedHandler(
                noteIntake,
                candidateWorkspace,
                approvedSnapshotWorkspace,
                workflowStatusEditor,
                ActivationMarkerTestFixtures.activatedMarkerStore());
        BridgeResponse approveResponse = markReviewedHandler.markReviewed(path, vaultReader);

        assertTrue(approveResponse.ok(), approveResponse.diagnostics().toString());
        CandidateSnapshot approved = approvedSnapshotWorkspace.read(identity).orElseThrow();

        ReleaseResult releaseResult = new BuildFromReviewHandler(
                approvedSnapshotWorkspace,
                ReleaseOutputStore.createNull(), ActivationMarkerTestFixtures.activatedMarkerStore()).buildFromReview(identity);

        assertTrue(releaseResult.ok(), releaseResult.message());

        new FilesystemManagedSiteInstaller(siteRoot).install(identity, approved);

        String installedRu = Files.readString(
                siteRoot.resolve("src/content/bibliography/ru/the-lean-startup.md"));
        String installedEn = Files.readString(
                siteRoot.resolve("src/content/bibliography/en/the-lean-startup.md"));
        assertTrue(installedRu.contains("use: \"Explains how to test demand before scaling a product bet.\"\n"));
        assertTrue(installedEn.contains("use: \"Explains how to test demand before scaling a product bet.\"\n"));
        assertTrue(installedRu.contains("""
                authors:
                  - "Eric Ries"
                publication: "Crown Business"
                publicationDate: "2011-09-13"
                readingStatus: "finished"
                """));
        assertTrue(installedEn.contains("""
                authors:
                  - "Eric Ries"
                publication: "Crown Business"
                publicationDate: "2011-09-13"
                readingStatus: "finished"
                """));
    }

}
