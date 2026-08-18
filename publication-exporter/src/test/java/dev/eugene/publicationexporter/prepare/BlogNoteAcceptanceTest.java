package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandler;
import dev.eugene.publicationexporter.buildfromreview.ReleaseResult;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.legacy.ActivationMarkerTestFixtures;
import dev.eugene.publicationexporter.markreviewed.MarkReviewedHandler;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultAssetReader;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;
import dev.eugene.publicationexporter.workflow.NullWorkflowStatusEditor;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlogNoteAcceptanceTest {

    private static final String VALID_NOTE = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: note
            publicId: my-note
            id: 91aa-my-note
            title: My Note
            description: A short observation.
            ---
            A short observation body.""";

    @Test
    void blogNoteCompletesPrepareApproveAndReleaseThroughTheSamePathAsEssay() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-note.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_NOTE));
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull();
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        TranslationWorker translationWorker =
                TranslationWorker.createNull("Translated body", List.of(
                        PublicField.of("title", "Translated title"),
                        PublicField.of("description", "Translated description.")));
        PrepareHandler prepareHandler = new PrepareHandler(
                noteIntake, translationWorker, candidateWorkspace, approvedSnapshotWorkspace, WorkflowStatusEditor.createNull());

        BridgeResponse prepareResponse = prepareHandler.prepare(path, vaultReader, vaultAssetReader);

        assertTrue(prepareResponse.ok(), prepareResponse.diagnostics().toString());
        PublicationIdentity identity = PublicationIdentity.of("blog", "note", "my-note");
        assertEquals(identity, prepareResponse.identity());

        WorkflowStatusEditor markdownEditor = new NullWorkflowStatusEditor(Map.of(path, VALID_NOTE));
        MarkReviewedHandler markReviewedHandler =
                new MarkReviewedHandler(noteIntake, candidateWorkspace, approvedSnapshotWorkspace, markdownEditor,
                        ActivationMarkerTestFixtures.activatedMarkerStore());
        BridgeResponse approveResponse = markReviewedHandler.markReviewed(path, vaultReader);

        assertTrue(approveResponse.ok());
        assertTrue(approvedSnapshotWorkspace.read(identity).isPresent());

        ReleaseResult releaseResult = new BuildFromReviewHandler(
                approvedSnapshotWorkspace, ReleaseOutputStore.createNull(), ActivationMarkerTestFixtures.activatedMarkerStore()).buildFromReview(identity);

        assertTrue(releaseResult.ok());
    }

}
