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
    void currentSchemaApprovedContentWithoutMigrationArtifactsRemainsCurrent() {
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, currentSchemaSnapshot());

        SchemaActivationCheck check = SchemaActivationGuard.check(
                approved, new NullCandidateWorkspace(), ActivationMarkerStore.createNull(),
                new NullMigrationJournalStore(), new NullMigrationCatalogStore());

        assertTrue(check.isCurrent());
    }

    @Test
    void currentSchemaCandidateContentWithoutMigrationArtifactsRemainsCurrent() {
        CandidateWorkspace candidate = new NullCandidateWorkspace();
        candidate.install(IDENTITY, currentSchemaSnapshot(), List.of());

        SchemaActivationCheck check = SchemaActivationGuard.check(
                new NullApprovedSnapshotWorkspace(), candidate, ActivationMarkerStore.createNull(),
                new NullMigrationJournalStore(), new NullMigrationCatalogStore());

        assertTrue(check.isCurrent());
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

    @Test
    void completeSealedGenerationWithMatchingApprovedTripleIsCurrent() {
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, completeSnapshot());
        MigrationGeneration sealed = new MigrationGeneration(
                "a".repeat(64), List.of(IDENTITY), 1, MigrationState.SEALED);
        NullMigrationJournalStore journal = new NullMigrationJournalStore();
        journal.save(sealed, new MigrationPreimage(
                new MigrationGeneration("a".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING),
                java.util.Map.of(), java.util.Map.of(IDENTITY, completeSnapshot())));
        NullMigrationCatalogStore catalog = new NullMigrationCatalogStore(sealed);

        SchemaActivationCheck check = SchemaActivationGuard.check(approved,
                new NullCandidateWorkspace(), ActivationMarkerStore.createNull(
                        new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z"))),
                journal, catalog);

        assertTrue(check.isCurrent());
    }

    @Test
    void sealedGenerationRejectsDifferentSelfConsistentApprovedContent() {
        CandidateSnapshot journaled = completeSnapshot("journaled");
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, completeSnapshot("replaced"));
        MigrationGeneration sealed = new MigrationGeneration(
                "a".repeat(64), List.of(IDENTITY), 1, MigrationState.SEALED);
        NullMigrationJournalStore journal = new NullMigrationJournalStore();
        journal.save(sealed, new MigrationPreimage(
                new MigrationGeneration("a".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING),
                java.util.Map.of(), java.util.Map.of(IDENTITY, journaled)));

        SchemaActivationCheck check = SchemaActivationGuard.check(approved,
                new NullCandidateWorkspace(), ActivationMarkerStore.createNull(
                        new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z"))),
                journal, new NullMigrationCatalogStore(sealed));

        assertTrue(check.isLegacy());
    }

    @Test
    void laterCurrentSchemaApprovalDoesNotInvalidateSealedMigrationEvidence() {
        PublicationIdentity later = PublicationIdentity.of("blog", "essay", "later");
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, completeSnapshot());
        approved.install(later, currentSchemaSnapshot(later, "later"));
        MigrationGeneration sealed = new MigrationGeneration(
                "a".repeat(64), List.of(IDENTITY), 1, MigrationState.SEALED);
        NullMigrationJournalStore journal = new NullMigrationJournalStore();
        journal.save(sealed, new MigrationPreimage(
                new MigrationGeneration("a".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING),
                java.util.Map.of(), java.util.Map.of(IDENTITY, completeSnapshot())));

        SchemaActivationCheck check = SchemaActivationGuard.check(approved,
                new NullCandidateWorkspace(), ActivationMarkerStore.createNull(
                        new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z"))),
                journal, new NullMigrationCatalogStore(sealed));

        assertTrue(check.isCurrent());
    }

    @Test
    void laterLegacyShapedApprovalInvalidatesSealedMigrationEvidence() {
        PublicationIdentity later = PublicationIdentity.of("blog", "essay", "later");
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, completeSnapshot());
        approved.install(later, completeSnapshot(later, "later"));
        MigrationGeneration sealed = new MigrationGeneration(
                "a".repeat(64), List.of(IDENTITY), 1, MigrationState.SEALED);
        NullMigrationJournalStore journal = new NullMigrationJournalStore();
        journal.save(sealed, new MigrationPreimage(
                new MigrationGeneration("a".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING),
                java.util.Map.of(), java.util.Map.of(IDENTITY, completeSnapshot())));

        SchemaActivationCheck check = SchemaActivationGuard.check(approved,
                new NullCandidateWorkspace(), ActivationMarkerStore.createNull(
                        new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z"))),
                journal, new NullMigrationCatalogStore(sealed));

        assertTrue(check.isLegacy());
    }

    @Test
    void currentSchemaReapprovalOfMigratedIdentityPreservesSealedMigrationEvidence() {
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, currentSchemaSnapshot(IDENTITY, "reapproved"));
        MigrationGeneration sealed = new MigrationGeneration(
                "a".repeat(64), List.of(IDENTITY), 1, MigrationState.SEALED);
        NullMigrationJournalStore journal = new NullMigrationJournalStore();
        journal.save(sealed, new MigrationPreimage(
                new MigrationGeneration("a".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING),
                java.util.Map.of(), java.util.Map.of(IDENTITY, completeSnapshot())));

        SchemaActivationCheck check = SchemaActivationGuard.check(approved,
                new NullCandidateWorkspace(), ActivationMarkerStore.createNull(
                        new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z"))),
                journal, new NullMigrationCatalogStore(sealed));

        assertTrue(check.isCurrent());
    }

    @Test
    void unsealedGenerationIsLegacyEvenWithAValidMarker() {
        ApprovedSnapshotWorkspace approved = new NullApprovedSnapshotWorkspace();
        approved.install(IDENTITY, completeSnapshot());
        MigrationGeneration running = new MigrationGeneration(
                "b".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING);
        NullMigrationJournalStore journal = new NullMigrationJournalStore();
        journal.save(running, new MigrationPreimage(running, java.util.Map.of(),
                java.util.Map.of(IDENTITY, completeSnapshot())));

        SchemaActivationCheck check = SchemaActivationGuard.check(approved,
                new NullCandidateWorkspace(), ActivationMarkerStore.createNull(
                        new ActivationMarker(1, "b".repeat(64), Instant.parse("2026-08-18T00:00:00Z"))),
                journal, new NullMigrationCatalogStore());

        assertTrue(check.isLegacy());
        assertTrue(check.blockingReason().contains("roll forward"));
    }

    private static CandidateSnapshot someSnapshot() {
        ReferenceMap referenceMap = ReferenceMap.of(IDENTITY,
                ContentHash.sha256Hex("ru"), ContentHash.sha256Hex("en"),
                "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
        return CandidateSnapshot.of("ru", "en", List.of(), List.of(), "", referenceMap);
    }

    private static CandidateSnapshot completeSnapshot() {
        return completeSnapshot("body");
    }

    private static CandidateSnapshot currentSchemaSnapshot() {
        return currentSchemaSnapshot(IDENTITY, "current");
    }

    private static CandidateSnapshot currentSchemaSnapshot(PublicationIdentity identity, String body) {
        String ru = body + "-ru";
        String en = body + "-en";
        ReferenceMap referenceMap = ReferenceMap.of(identity, body + "-source-id",
                ContentHash.sha256Hex(ru), ContentHash.sha256Hex(en),
                ContentHash.sha256Hex("[]"), ContentHash.sha256Hex("[]"),
                ContentHash.sha256Hex(""), List.of(), ContentHash.sha256Hex(body + "-source-body"));
        return CandidateSnapshot.of(ru, en, List.of(), List.of(), "", referenceMap);
    }

    private static CandidateSnapshot completeSnapshot(String body) {
        return completeSnapshot(IDENTITY, body);
    }

    private static CandidateSnapshot completeSnapshot(PublicationIdentity identity, String body) {
        String ru = "ru-" + body;
        String en = "en-" + body;
        ReferenceMap referenceMap = ReferenceMap.of(identity, "source-id-" + body,
                ContentHash.sha256Hex(ru), ContentHash.sha256Hex(en),
                ContentHash.sha256Hex("[]"), ContentHash.sha256Hex("[]"),
                ContentHash.sha256Hex(""), List.of());
        return CandidateSnapshot.of(ru, en, List.of(), List.of(), "", referenceMap);
    }
}
