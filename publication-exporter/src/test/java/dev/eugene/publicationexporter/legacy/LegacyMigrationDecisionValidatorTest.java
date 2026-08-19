package dev.eugene.publicationexporter.legacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyMigrationDecisionValidatorTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "target");
    private static final PublicationIdentity IDENTITY_2 = PublicationIdentity.of("blog", "essay", "second");

    @Test
    void validatesSeparateHumanDecisionBoundToCurrentInventory() throws Exception {
        LegacyWorkspaceInventoryHandler inventory = inventoryWith(IDENTITY, "source-1");
        String decisionJson = new ObjectMapper().writeValueAsString(Map.of(
                "schemaVersion", 1, "inventorySha256", inventory.inspect().inventorySha256()));

        MigrationDecisionSet decision = new LegacyMigrationDecisionValidator(
                inventory, new LegacyMigrationDecisionCodec()).validate(decisionJson);

        assertEquals(inventory.inspect().inventorySha256(), decision.inventorySha256());
    }

    @Test
    void rejectsHumanDecisionWhenWorkspaceChangesAfterItsFingerprintWasCaptured() throws Exception {
        NullApprovedSnapshotWorkspace approved = approvedWorkspaceWith(IDENTITY, "source-1");
        LegacyWorkspaceInventoryHandler inventory = new LegacyWorkspaceInventoryHandler(
                approved, new NullCandidateWorkspace());
        String oldDecision = decisionFor(inventory.inspect().inventorySha256());
        approved.install(IDENTITY_2, snapshotWithSourceId(IDENTITY_2, "source-2"));

        assertThrows(LegacyMigrationDecisionException.class,
                () -> new LegacyMigrationDecisionValidator(
                        inventory, new LegacyMigrationDecisionCodec()).validate(oldDecision));
    }

    @Test
    void everyValidatorConstructionSupportsItsPublicValidationProtocol() {
        assertThrows(NullPointerException.class,
                () -> new LegacyMigrationDecisionValidator(null, new LegacyMigrationDecisionCodec()));
    }

    private static String decisionFor(String fingerprint) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "schemaVersion", 1, "inventorySha256", fingerprint));
    }

    private static LegacyWorkspaceInventoryHandler inventoryWith(
            PublicationIdentity identity, String sourceId) {
        return new LegacyWorkspaceInventoryHandler(
                approvedWorkspaceWith(identity, sourceId), new NullCandidateWorkspace());
    }

    private static NullApprovedSnapshotWorkspace approvedWorkspaceWith(
            PublicationIdentity identity, String sourceId) {
        NullApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(identity, snapshotWithSourceId(identity, sourceId));
        return approved;
    }

    private static CandidateSnapshot snapshotWithSourceId(PublicationIdentity identity, String sourceId) {
        ReferenceMap referenceMap = ReferenceMap.of(identity, sourceId,
                ContentHash.sha256Hex("ru"), ContentHash.sha256Hex("en"),
                "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
        return CandidateSnapshot.of("ru", "en", List.of(), List.of(), "", referenceMap);
    }
}
