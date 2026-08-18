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

class AlbumAcceptanceTest {

    private static final String VALID_ALBUM = """
            ---
            publish: true
            publicCollection: music
            publicContentType: album
            publicId: nocturnal-lines
            id: 7d2e-nocturnal-lines
            title: Ночные линии
            description: Русское описание альбома.
            artist: Алина Орлова
            work: Ночные линии
            context: Музыкальный контекст.
            association: Связь с ночным городом.
            format: Винил
            care: Слушать внимательно.
            listenFor:
              - ритм
              - фактура
            releaseDate: 2024-04-01
            genreTags:
              - ambient
              - electronic
            streamingUrl: "https://example.test/nocturnal-lines"
            bandcampEmbedUrl: "https://bandcamp.test/embed/nocturnal-lines"
            ---
            Русское тело альбома.
            """;

    @TempDir
    Path siteRoot;

    @Test
    void albumCompletesAdmissionThroughSiteInstallation() throws Exception {
        VaultRelativePath path = VaultRelativePath.of("music/nocturnal-lines.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ALBUM));
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull();
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        TranslationWorker translationWorker = TranslationWorker.createNull(
                "English album body.",
                List.of(
                        PublicField.of("title", "Nocturnal Lines"),
                        PublicField.of("description", "An English album description."),
                        PublicField.of("context", "A musical context."),
                        PublicField.of("association", "An association with the night city."),
                        PublicField.of("format", "Vinyl"),
                        PublicField.of("care", "Listen closely."),
                        PublicField.of("listenFor[0]", "rhythm"),
                        PublicField.of("listenFor[1]", "texture")));
        WorkflowStatusEditor workflowStatusEditor = new NullWorkflowStatusEditor(Map.of(path, VALID_ALBUM));
        PrepareHandler prepareHandler = new PrepareHandler(
                noteIntake,
                translationWorker,
                candidateWorkspace,
                approvedSnapshotWorkspace,
                WorkflowStatusEditor.createNull());

        BridgeResponse prepareResponse = prepareHandler.prepare(path, vaultReader, vaultAssetReader);

        assertTrue(prepareResponse.ok(), prepareResponse.diagnostics().toString());
        PublicationIdentity identity = PublicationIdentity.of("music", "album", "nocturnal-lines");
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
                ReleaseOutputStore.createNull(), ActivationMarkerTestFixtures.activatedMarkerStore()).buildFromReview(identity);

        assertTrue(releaseResult.ok(), releaseResult.message());

        new FilesystemManagedSiteInstaller(siteRoot).install(identity, approved);

        String installedRu = Files.readString(
                siteRoot.resolve("src/content/music/ru/nocturnal-lines.md"));
        String installedEn = Files.readString(
                siteRoot.resolve("src/content/music/en/nocturnal-lines.md"));
        assertTrue(installedEn.contains("English album body."));
        assertTrue(installedRu.contains("""
                title: "Ночные линии"
                description: "Русское описание альбома."
                context: "Музыкальный контекст."
                association: "Связь с ночным городом."
                format: "Винил"
                care: "Слушать внимательно."
                listenFor:
                  - "ритм"
                  - "фактура"
                """));
        assertTrue(installedEn.contains("""
                title: "Nocturnal Lines"
                description: "An English album description."
                context: "A musical context."
                association: "An association with the night city."
                format: "Vinyl"
                care: "Listen closely."
                listenFor:
                  - "rhythm"
                  - "texture"
                """));

        String expectedInvariantBlock = """
                artist: "Алина Орлова"
                work: "Ночные линии"
                releaseDate: "2024-04-01"
                streamingUrl: "https://example.test/nocturnal-lines"
                bandcampEmbedUrl: "https://bandcamp.test/embed/nocturnal-lines"
                genreTags:
                  - "ambient"
                  - "electronic"
                reviewType: "album"
                """;
        assertEquals(expectedInvariantBlock, extractInvariantBlock(installedRu));
        assertEquals(expectedInvariantBlock, extractInvariantBlock(installedEn));
    }

    private String extractInvariantBlock(String installed) {
        int start = installed.indexOf("artist: ");
        int end = installed.indexOf("---\n", start);
        return installed.substring(start, end);
    }
}
