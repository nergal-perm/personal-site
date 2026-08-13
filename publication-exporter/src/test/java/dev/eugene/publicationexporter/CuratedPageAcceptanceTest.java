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

class CuratedPageAcceptanceTest {

    private static final String VALID_ABOUT_PAGE = """
            ---
            publish: true
            publicCollection: editorial
            publicContentType: curated_page
            publicId: about
            editorialPage: about
            id: page-about
            title: Обо мне
            publicSearchable: true
            ---
            ## Кратко

            Кратко.

            ## Eyebrow

            Бровь.

            ## Лид

            Лид.

            ## Принципы

            ### Первый

            Принцип.

            ## Колофон

            Колофон.
            """;

    @TempDir
    Path siteRoot;

    @Test
    void aboutPageCompletesAdmissionThroughSiteInstallation() throws Exception {
        VaultRelativePath path = VaultRelativePath.of("editorial/about.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ABOUT_PAGE));
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull();
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        TranslationWorker translationWorker = TranslationWorker.createNull(
                "English about page body.",
                List.of(
                        PublicField.of("title", "About Me"),
                        PublicField.of("summary", "Summary."),
                        PublicField.of("eyebrow", "Eyebrow."),
                        PublicField.of("lead", "Lead."),
                        PublicField.of("principles[0].title", "First"),
                        PublicField.of("principles[0].text", "Principle."),
                        PublicField.of("colophon", "Colophon.")));
        WorkflowStatusEditor workflowStatusEditor = new NullWorkflowStatusEditor(Map.of(path, VALID_ABOUT_PAGE));
        PrepareHandler prepareHandler = new PrepareHandler(
                noteIntake,
                translationWorker,
                candidateWorkspace,
                approvedSnapshotWorkspace,
                WorkflowStatusEditor.createNull());

        BridgeResponse prepareResponse = prepareHandler.prepare(path, vaultReader, vaultAssetReader);

        assertTrue(prepareResponse.ok(), prepareResponse.diagnostics().toString());
        PublicationIdentity identity = PublicationIdentity.of("editorial", "curated_page", "about");
        assertEquals(identity, prepareResponse.identity());

        MarkReviewedHandler markReviewedHandler = new MarkReviewedHandler(
                noteIntake,
                candidateWorkspace,
                approvedSnapshotWorkspace,
                workflowStatusEditor);
        BridgeResponse approveResponse = markReviewedHandler.markReviewed(path, vaultReader);

        assertTrue(approveResponse.ok(), approveResponse.diagnostics().toString());
        CandidateSnapshot approved = approvedSnapshotWorkspace.read(identity).orElseThrow();

        ReleaseResult releaseResult = new BuildFromReviewHandler(
                approvedSnapshotWorkspace,
                ReleaseOutputStore.createNull()).buildFromReview(identity);

        assertTrue(releaseResult.ok(), releaseResult.message());

        new FilesystemManagedSiteInstaller(siteRoot).install(identity, approved);

        String installedRu = Files.readString(siteRoot.resolve("src/data/pages/ru/about.json"));
        String installedEn = Files.readString(siteRoot.resolve("src/data/pages/en/about.json"));
        assertTrue(installedRu.contains("\"title\" : \"Обо мне\""));
        assertTrue(installedRu.contains("\"summary\" : \"Кратко.\""));
        assertTrue(installedRu.contains("\"searchable\" : true"));
        assertTrue(installedEn.contains("\"title\" : \"About Me\""));
        assertTrue(installedEn.contains("\"summary\" : \"Summary.\""));
        assertTrue(installedEn.contains("\"searchable\" : true"));
    }
}
