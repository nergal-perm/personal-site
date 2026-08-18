package dev.eugene.publicationexporter;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandler;
import dev.eugene.publicationexporter.buildfromreview.ReleaseResult;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.legacy.ActivationMarker;
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
import dev.eugene.publicationexporter.legacy.ActivationMarkerTestFixtures;
import dev.eugene.publicationexporter.markreviewed.MarkReviewedHandler;
import dev.eugene.publicationexporter.prepare.PrepareHandler;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;
import dev.eugene.publicationexporter.release.NullReleaseOutputStore;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultAssetReader;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.NullWorkflowStatusEditor;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LateBoundTargetActivationAcceptanceTest {

    private static final VaultRelativePath REFERRER_PATH = VaultRelativePath.of("blog/referrer.md");
    private static final VaultRelativePath TARGET_PATH = VaultRelativePath.of("blog/Target.md");
    private static final PublicationIdentity REFERRER_IDENTITY =
            PublicationIdentity.of("blog", "essay", "referrer");
    private static final PublicationIdentity TARGET_IDENTITY =
            PublicationIdentity.of("blog", "note", "target-note");
    private static final String TARGET_SOURCE_ID = "source-target-note";
    private static final String REFERRER = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: referrer
            id: source-referrer
            title: Ссылающаяся заметка
            description: Проверяет позднее связывание цели.
            ---
            See [[Target|Цель]].""";
    private static final String TARGET = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: note
            publicId: target-note
            id: source-target-note
            title: Целевая заметка
            description: Цель семантической ссылки.
            ---
            Target body.""";

    @Test
    void referrerActivatesDeactivatesAndReactivatesAsTargetApprovalChangesWithoutReferrerRewrites() {
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        VaultReader referrerOnlyVault = VaultReader.createNull(Map.of(REFERRER_PATH, REFERRER));

        BridgeResponse initialPrepare = prepareHandler(
                noteIntake,
                TranslationWorker.createNull("See Target.", translatedFields("Referrer", "Late-bound target.")),
                candidateWorkspace,
                approvedSnapshotWorkspace).prepare(
                        REFERRER_PATH, referrerOnlyVault, VaultAssetReader.createNull());
        BridgeResponse initialApproval = markReviewed(
                noteIntake, candidateWorkspace, approvedSnapshotWorkspace,
                REFERRER_PATH, REFERRER, referrerOnlyVault);

        assertTrue(initialPrepare.ok(), initialPrepare.diagnostics().toString());
        assertTrue(initialApproval.ok(), initialApproval.diagnostics().toString());
        CandidateSnapshot initiallyApproved = approvedSnapshotWorkspace.read(REFERRER_IDENTITY).orElseThrow();
        assertEquals("See Цель.", initiallyApproved.ruBody());
        assertEquals("See Target.", initiallyApproved.enBody());
        NullReleaseOutputStore initialOutput = new NullReleaseOutputStore();
        ReleaseResult initialRelease = new BuildFromReviewHandler(
                approvedSnapshotWorkspace, initialOutput, ActivationMarkerTestFixtures.activatedMarkerStore()).buildFromReview(REFERRER_IDENTITY);
        assertTrue(initialRelease.ok(), initialRelease.message());
        assertEquals("See Цель.", initialOutput.installed().get(REFERRER_IDENTITY).ruBody());
        assertEquals("See Target.", initialOutput.installed().get(REFERRER_IDENTITY).enBody());

        VaultReader admittedTargetVault = VaultReader.createNull(Map.of(
                REFERRER_PATH, REFERRER,
                TARGET_PATH, TARGET));
        String translatedMarker = "See [\uE000Target\uEC80](ref:" + TARGET_SOURCE_ID + ").";
        BridgeResponse markerPrepare = prepareHandler(
                noteIntake,
                TranslationWorker.createNull(
                        translatedMarker, translatedFields("Referrer", "Late-bound target.")),
                candidateWorkspace,
                approvedSnapshotWorkspace).prepare(
                        REFERRER_PATH, admittedTargetVault, VaultAssetReader.createNull());
        BridgeResponse markerApproval = markReviewed(
                noteIntake, candidateWorkspace, approvedSnapshotWorkspace,
                REFERRER_PATH, REFERRER, admittedTargetVault);

        assertTrue(markerPrepare.ok(), markerPrepare.diagnostics().toString());
        assertTrue(markerApproval.ok(), markerApproval.diagnostics().toString());
        CandidateSnapshot markerApproved = approvedSnapshotWorkspace.read(REFERRER_IDENTITY).orElseThrow();
        assertEquals("See [Цель](ref:source-target-note).", markerApproved.ruBody());
        assertEquals("See [Target](ref:source-target-note).", markerApproved.enBody());
        String approvedReferrerReferenceMapHash = referenceMapHash(markerApproved);
        long preparedReferrerCount = preparedCount(candidateWorkspace, REFERRER_IDENTITY);
        NullReleaseOutputStore markerWithoutTargetOutput = new NullReleaseOutputStore();
        ReleaseResult markerWithoutTargetRelease = new BuildFromReviewHandler(
                approvedSnapshotWorkspace, markerWithoutTargetOutput, ActivationMarkerTestFixtures.activatedMarkerStore()).buildFromReview(REFERRER_IDENTITY);

        assertTrue(markerWithoutTargetRelease.ok(), markerWithoutTargetRelease.message());
        assertEquals("See Цель.", markerWithoutTargetOutput.installed().get(REFERRER_IDENTITY).ruBody());
        assertEquals("See Target.", markerWithoutTargetOutput.installed().get(REFERRER_IDENTITY).enBody());

        BridgeResponse targetPrepare = prepareHandler(
                noteIntake,
                TranslationWorker.createNull(
                        "Target body in English.", translatedFields("Target note", "Semantic link target.")),
                candidateWorkspace,
                approvedSnapshotWorkspace).prepare(
                        TARGET_PATH, admittedTargetVault, VaultAssetReader.createNull());
        BridgeResponse targetApproval = markReviewed(
                noteIntake, candidateWorkspace, approvedSnapshotWorkspace,
                TARGET_PATH, TARGET, admittedTargetVault);

        assertTrue(targetPrepare.ok(), targetPrepare.diagnostics().toString());
        assertTrue(targetApproval.ok(), targetApproval.diagnostics().toString());
        assertEquals(preparedReferrerCount, preparedCount(candidateWorkspace, REFERRER_IDENTITY));
        NullReleaseOutputStore activeOutput = new NullReleaseOutputStore();
        ReleaseResult activeRelease = new BuildFromReviewHandler(
                approvedSnapshotWorkspace, activeOutput, ActivationMarkerTestFixtures.activatedMarkerStore()).buildFromReview(REFERRER_IDENTITY);
        assertTrue(activeRelease.ok(), activeRelease.message());
        assertEquals("See [Цель](/ru/notes/target-note/).",
                activeOutput.installed().get(REFERRER_IDENTITY).ruBody());
        assertEquals("See [Target](/en/notes/target-note/).",
                activeOutput.installed().get(REFERRER_IDENTITY).enBody());
        assertEquals(approvedReferrerReferenceMapHash,
                referenceMapHash(approvedSnapshotWorkspace.read(REFERRER_IDENTITY).orElseThrow()));

        NullApprovedSnapshotWorkspace referrerOnlyApprovedWorkspace = new NullApprovedSnapshotWorkspace();
        referrerOnlyApprovedWorkspace.install(REFERRER_IDENTITY, markerApproved);
        NullReleaseOutputStore inactiveOutput = new NullReleaseOutputStore();
        ReleaseResult inactiveRelease = new BuildFromReviewHandler(
                referrerOnlyApprovedWorkspace, inactiveOutput, ActivationMarkerTestFixtures.activatedMarkerStore()).buildFromReview(REFERRER_IDENTITY);

        assertTrue(inactiveRelease.ok(), inactiveRelease.message());
        assertEquals("See Цель.", inactiveOutput.installed().get(REFERRER_IDENTITY).ruBody());
        assertEquals("See Target.", inactiveOutput.installed().get(REFERRER_IDENTITY).enBody());
        assertEquals(approvedReferrerReferenceMapHash,
                referenceMapHash(referrerOnlyApprovedWorkspace.read(REFERRER_IDENTITY).orElseThrow()));
        assertEquals(approvedReferrerReferenceMapHash,
                referenceMapHash(approvedSnapshotWorkspace.read(REFERRER_IDENTITY).orElseThrow()));
    }

    private static PrepareHandler prepareHandler(
            NoteIntake noteIntake,
            TranslationWorker translationWorker,
            NullCandidateWorkspace candidateWorkspace,
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
        return new PrepareHandler(
                noteIntake,
                translationWorker,
                candidateWorkspace,
                approvedSnapshotWorkspace,
                WorkflowStatusEditor.createNull(),
                ActivationMarkerStore.createNull(
                        new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z"))));
    }

    private static BridgeResponse markReviewed(
            NoteIntake noteIntake,
            NullCandidateWorkspace candidateWorkspace,
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace,
            VaultRelativePath notePath,
            String source,
            VaultReader vaultReader) {
        return new MarkReviewedHandler(
                noteIntake,
                candidateWorkspace,
                approvedSnapshotWorkspace,
                new NullWorkflowStatusEditor(Map.of(notePath, source)),
                ActivationMarkerTestFixtures.activatedMarkerStore())
                .markReviewed(notePath, vaultReader);
    }

    private static List<PublicField> translatedFields(String title, String description) {
        return List.of(PublicField.of("title", title), PublicField.of("description", description));
    }

    private static String referenceMapHash(CandidateSnapshot snapshot) {
        return ContentHash.sha256Hex(ReferenceMapCodec.write(snapshot.referenceMap()));
    }

    private static long preparedCount(
            NullCandidateWorkspace candidateWorkspace, PublicationIdentity identity) {
        return candidateWorkspace.installed().stream()
                .filter(candidate -> candidate.identity().equals(identity))
                .count();
    }
}
