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
import dev.eugene.publicationexporter.legacy.ActivationMarker;
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
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

class ConceptAcceptanceTest {

    private static final String VALID_CONCEPT = """
            ---
            publish: true
            publicCollection: concepts
            publicContentType: concept
            publicId: core-concept
            id: 7d2e-core-concept
            title: Основное понятие
            description: Краткое русское описание понятия.
            notThis: Не следует путать с близким термином.
            relations:
              - name: родительский контекст
                relation: включает понятие
              - name: связанный контекст
                relation: уточняет границы
            examples:
              - Первый русский пример.
              - Второй русский пример.
            ---
            Текст русского понятия.
            """;

    @TempDir
    Path siteRoot;

    @Test
    void conceptCompletesAdmissionThroughSiteInstallation() throws Exception {
        VaultRelativePath path = VaultRelativePath.of("concepts/core-concept.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_CONCEPT));
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull();
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        TranslationWorker translationWorker = TranslationWorker.createNull(
                "English concept body.",
                List.of(
                        PublicField.of("title", "Core Concept"),
                        PublicField.of("description", "A concise English description of the concept."),
                        PublicField.of("notThis", "Do not confuse it with a neighboring term."),
                        PublicField.of("relations[0].name", "parent context"),
                        PublicField.of("relations[0].relation", "contains the concept"),
                        PublicField.of("relations[1].name", "related context"),
                        PublicField.of("relations[1].relation", "clarifies the boundaries"),
                        PublicField.of("examples[0]", "First English example."),
                        PublicField.of("examples[1]", "Second English example.")));
        WorkflowStatusEditor workflowStatusEditor = new NullWorkflowStatusEditor(Map.of(path, VALID_CONCEPT));
        PrepareHandler prepareHandler = new PrepareHandler(
                noteIntake,
                translationWorker,
                candidateWorkspace,
                approvedSnapshotWorkspace,
                WorkflowStatusEditor.createNull());

        BridgeResponse prepareResponse = prepareHandler.prepare(path, vaultReader, vaultAssetReader);

        assertTrue(prepareResponse.ok(), prepareResponse.diagnostics().toString());
        PublicationIdentity identity = PublicationIdentity.of("concepts", "concept", "core-concept");
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
                ReleaseOutputStore.createNull(), activatedMarkerStore()).buildFromReview(identity);

        assertTrue(releaseResult.ok(), releaseResult.message());

        new FilesystemManagedSiteInstaller(siteRoot).install(identity, approved);

        String installedRu = Files.readString(
                siteRoot.resolve("src/content/concepts/ru/core-concept.md"));
        String installedEn = Files.readString(
                siteRoot.resolve("src/content/concepts/en/core-concept.md"));
        assertTrue(installedRu.contains("""
                notThis: "Не следует путать с близким термином."
                relations:
                  - name: "родительский контекст"
                    relation: "включает понятие"
                  - name: "связанный контекст"
                    relation: "уточняет границы"
                examples:
                  - "Первый русский пример."
                  - "Второй русский пример."
                """));
        assertTrue(installedEn.contains("""
                notThis: "Do not confuse it with a neighboring term."
                relations:
                  - name: "parent context"
                    relation: "contains the concept"
                  - name: "related context"
                    relation: "clarifies the boundaries"
                examples:
                  - "First English example."
                  - "Second English example."
                """));
        assertTrue(installedRu.endsWith("Текст русского понятия.\n"));
        assertTrue(installedEn.endsWith("English concept body."));
    }

    private static ActivationMarkerStore activatedMarkerStore() {
        return ActivationMarkerStore.createNull(
                new ActivationMarker(1, "a".repeat(64), java.time.Instant.parse("2026-08-18T00:00:00Z")));
    }
}
