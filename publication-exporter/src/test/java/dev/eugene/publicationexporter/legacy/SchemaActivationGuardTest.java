package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaActivationGuardTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "target");

    @Test
    void emptyWorkspaceWithNoMarkerIsCurrent() {
        SchemaActivationCheck check = SchemaActivationGuard.check(
                new NullApprovedSnapshotWorkspace(), new NullCandidateWorkspace(), ActivationMarkerStore.createNull());

        assertFalse(check.requiresMigration());
    }

    @Test
    void approvedContentWithNoMarkerIsLegacy() {
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, someSnapshot());

        SchemaActivationCheck check = SchemaActivationGuard.check(
                approved, new NullCandidateWorkspace(), ActivationMarkerStore.createNull());

        assertTrue(check.requiresMigration());
    }

    @Test
    void candidateContentWithNoMarkerIsLegacy() {
        CandidateWorkspace candidate = new NullCandidateWorkspace();
        candidate.install(IDENTITY, someSnapshot(), List.of());

        SchemaActivationCheck check = SchemaActivationGuard.check(
                new NullApprovedSnapshotWorkspace(), candidate, ActivationMarkerStore.createNull());

        assertTrue(check.requiresMigration());
    }

    @Test
    void contentWithAValidMarkerIsCurrent() {
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, someSnapshot());
        ActivationMarker validMarker =
                new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z"));

        SchemaActivationCheck check = SchemaActivationGuard.check(
                approved, new NullCandidateWorkspace(), ActivationMarkerStore.createNull(validMarker));

        assertFalse(check.requiresMigration());
    }

    @Test
    void contentWithAnInvalidMarkerIsLegacy() {
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, someSnapshot());
        ActivationMarker invalidMarker =
                new ActivationMarker(1, "not-a-sha256", Instant.parse("2026-08-18T00:00:00Z"));

        SchemaActivationCheck check = SchemaActivationGuard.check(
                approved, new NullCandidateWorkspace(), ActivationMarkerStore.createNull(invalidMarker));

        assertTrue(check.requiresMigration());
    }

    @Test
    void approvedOnlyOverloadIgnoresCandidateContent() {
        CandidateWorkspace candidate = new NullCandidateWorkspace();
        candidate.install(IDENTITY, someSnapshot(), List.of());

        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        ActivationMarkerStore markerStore = ActivationMarkerStore.createNull();

        SchemaActivationCheck prepareCheck = SchemaActivationGuard.check(approved, candidate, markerStore);
        SchemaActivationCheck releaseCheck = SchemaActivationGuard.check(approved, markerStore);

        assertTrue(prepareCheck.requiresMigration());
        assertFalse(releaseCheck.requiresMigration());
    }

    private static CandidateSnapshot someSnapshot() {
        ReferenceMap referenceMap = ReferenceMap.of(IDENTITY,
                ContentHash.sha256Hex("ru"), ContentHash.sha256Hex("en"),
                "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
        return CandidateSnapshot.of("ru", "en", List.of(), List.of(), "", referenceMap);
    }
}
