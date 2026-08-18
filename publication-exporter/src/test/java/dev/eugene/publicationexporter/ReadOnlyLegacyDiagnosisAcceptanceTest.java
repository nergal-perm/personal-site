package dev.eugene.publicationexporter;

import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandler;
import dev.eugene.publicationexporter.buildfromreview.ReleaseResult;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
import dev.eugene.publicationexporter.legacy.ActivationMarkerTestFixtures;
import dev.eugene.publicationexporter.legacy.LegacyWorkspaceInventory;
import dev.eugene.publicationexporter.legacy.LegacyWorkspaceInventoryHandler;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.release.NullReleaseOutputStore;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyLegacyDiagnosisAcceptanceTest {

    private static final PublicationIdentity LEGACY_IDENTITY = PublicationIdentity.of("blog", "essay", "legacy-essay");
    private static final PublicationIdentity CURRENT_IDENTITY = PublicationIdentity.of("blog", "essay", "current-essay");

    @Test
    void currentEmptyWorkspaceFailsForOrdinaryReasonsNotTheSchemaGuard() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        BuildFromReviewHandler handler = new BuildFromReviewHandler(
                approved, ReleaseOutputStore.createNull(), ActivationMarkerStore.createNull());

        ReleaseResult result = handler.buildFromReview(CURRENT_IDENTITY);

        assertFalse(result.ok());
        assertEquals("No approved snapshot exists to release.", result.message());
    }

    @Test
    void legacyContentWithNoMarkerFailsClosedWithoutMutation() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(LEGACY_IDENTITY, snapshotWithNoSourceId(LEGACY_IDENTITY));
        NullReleaseOutputStore releaseOutputStore = new NullReleaseOutputStore();
        BuildFromReviewHandler handler = new BuildFromReviewHandler(
                approved, releaseOutputStore, ActivationMarkerStore.createNull());

        ReleaseResult result = handler.buildFromReview(LEGACY_IDENTITY);

        assertFalse(result.ok());
        assertTrue(releaseOutputStore.installed().isEmpty());
    }

    @Test
    void inventoryOverTheSameLegacyWorkspaceIsDeterministicAndNamesTheBlockedIdentity() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(LEGACY_IDENTITY, snapshotWithNoSourceId(LEGACY_IDENTITY));
        LegacyWorkspaceInventoryHandler inventoryHandler =
                new LegacyWorkspaceInventoryHandler(approved, new NullCandidateWorkspace());

        LegacyWorkspaceInventory first = inventoryHandler.inspect();
        LegacyWorkspaceInventory second = inventoryHandler.inspect();

        assertEquals(List.of(LEGACY_IDENTITY), first.approvedPairs());
        assertEquals(1, first.blockers().size());
        assertTrue(first.blockers().get(0).contains(LEGACY_IDENTITY.toString()));
        assertEquals(first.inventorySha256(), second.inventorySha256());
    }

    @Test
    void aValidActivationMarkerLetsALegacyShapedWorkspaceReleaseAndInstallNormallyAgain() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(LEGACY_IDENTITY, snapshotWithSourceId(LEGACY_IDENTITY, "vault-source-id-legacy"));
        NullReleaseOutputStore releaseOutputStore = new NullReleaseOutputStore();
        BuildFromReviewHandler handler = new BuildFromReviewHandler(
                approved, releaseOutputStore, ActivationMarkerTestFixtures.activatedMarkerStore());

        ReleaseResult result = handler.buildFromReview(LEGACY_IDENTITY);

        assertTrue(result.ok());
        assertTrue(releaseOutputStore.installed().containsKey(LEGACY_IDENTITY));
        assertEquals("ru body", releaseOutputStore.installed().get(LEGACY_IDENTITY).ruBody());
        assertEquals("en body", releaseOutputStore.installed().get(LEGACY_IDENTITY).enBody());
    }

    private static CandidateSnapshot snapshotWithSourceId(PublicationIdentity identity, String sourceId) {
        return snapshot(identity, Optional.of(sourceId));
    }

    private static CandidateSnapshot snapshotWithNoSourceId(PublicationIdentity identity) {
        return snapshot(identity, Optional.empty());
    }

    private static CandidateSnapshot snapshot(PublicationIdentity identity, Optional<String> sourceId) {
        ReferenceMap referenceMap = sourceId
                .map(id -> ReferenceMap.of(identity, id,
                        ContentHash.sha256Hex("ru body"), ContentHash.sha256Hex("en body"),
                        "ru-fields-hash", "en-fields-hash", "structured-hash", List.of()))
                .orElseGet(() -> ReferenceMap.of(identity,
                        ContentHash.sha256Hex("ru body"), ContentHash.sha256Hex("en body"),
                        "ru-fields-hash", "en-fields-hash", "structured-hash", List.of()));
        return CandidateSnapshot.of("ru body", "en body", List.of(), List.of(), "", referenceMap);
    }
}
