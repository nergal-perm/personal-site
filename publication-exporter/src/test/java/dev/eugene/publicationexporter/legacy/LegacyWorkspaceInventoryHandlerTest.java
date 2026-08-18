package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyWorkspaceInventoryHandlerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "target");

    @Test
    void inspectingAnEmptyWorkspaceReturnsAllFourListsEmpty() {
        LegacyWorkspaceInventoryHandler handler = new LegacyWorkspaceInventoryHandler(
                new NullApprovedSnapshotWorkspace(), new NullCandidateWorkspace());

        LegacyWorkspaceInventory inventory = handler.inspect();

        assertEquals(List.of(), inventory.approvedPairs());
        assertEquals(List.of(), inventory.candidatePairs());
        assertEquals(List.of(), inventory.ambiguities());
        assertEquals(List.of(), inventory.blockers());
    }

    @Test
    void inspectingIsRepeatableAndDeterministic() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, snapshotWithSourceId("vault-source-id-target"));
        LegacyWorkspaceInventoryHandler handler =
                new LegacyWorkspaceInventoryHandler(approved, new NullCandidateWorkspace());

        LegacyWorkspaceInventory first = handler.inspect();
        LegacyWorkspaceInventory second = handler.inspect();

        assertEquals(first.inventorySha256(), second.inventorySha256());
        assertEquals(List.of(IDENTITY), first.approvedPairs());
        assertTrue(first.blockers().isEmpty());
    }

    @Test
    void anApprovedOnlyIdentityIsNotAnAmbiguity() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, snapshotWithSourceId("vault-source-id-target"));
        LegacyWorkspaceInventoryHandler handler =
                new LegacyWorkspaceInventoryHandler(approved, new NullCandidateWorkspace());

        LegacyWorkspaceInventory inventory = handler.inspect();

        assertTrue(inventory.ambiguities().isEmpty());
    }

    @Test
    void anApprovedSnapshotWithNoRecordedSourceIdIsABlocker() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, snapshotWithNoSourceId());
        LegacyWorkspaceInventoryHandler handler =
                new LegacyWorkspaceInventoryHandler(approved, new NullCandidateWorkspace());

        LegacyWorkspaceInventory inventory = handler.inspect();

        assertEquals(1, inventory.blockers().size());
    }


    @Test
    void mismatchedSourceIdsBetweenApprovedAndCandidateForTheSameIdentityIsAnAmbiguity() {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, snapshotWithSourceId("vault-source-id-a"));
        NullCandidateWorkspace candidate = new NullCandidateWorkspace();
        candidate.install(IDENTITY, snapshotWithSourceId("vault-source-id-b"), List.of());
        LegacyWorkspaceInventoryHandler handler = new LegacyWorkspaceInventoryHandler(approved, candidate);

        LegacyWorkspaceInventory inventory = handler.inspect();

        assertEquals(List.of(IDENTITY + ": approved sourceId Optional[vault-source-id-a]"
                + " does not match candidate sourceId Optional[vault-source-id-b]"), inventory.ambiguities());
    }

    @Test
    void emptyWorkspaceHashIsStableAcrossRuns() {
        LegacyWorkspaceInventoryHandler first = new LegacyWorkspaceInventoryHandler(
                new NullApprovedSnapshotWorkspace(), new NullCandidateWorkspace());
        LegacyWorkspaceInventoryHandler second = new LegacyWorkspaceInventoryHandler(
                new NullApprovedSnapshotWorkspace(), new NullCandidateWorkspace());

        assertEquals(first.inspect().inventorySha256(), second.inspect().inventorySha256());
        assertTrue(first.inspect().inventorySha256().matches("^[0-9a-f]{64}$"));
    }

    private static CandidateSnapshot snapshotWithSourceId(String sourceId) {
        ReferenceMap referenceMap = ReferenceMap.of(IDENTITY, sourceId,
                ContentHash.sha256Hex("ru"), ContentHash.sha256Hex("en"),
                "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
        return CandidateSnapshot.of("ru", "en", List.of(), List.of(), "", referenceMap);
    }

    private static CandidateSnapshot snapshotWithNoSourceId() {
        ReferenceMap referenceMap = ReferenceMap.of(IDENTITY,
                ContentHash.sha256Hex("ru"), ContentHash.sha256Hex("en"),
                "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
        return CandidateSnapshot.of("ru", "en", List.of(), List.of(), "", referenceMap);
    }
}
