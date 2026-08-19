package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FilesystemMigrationAcceptanceTest {

    private static final PublicationIdentity IDENTITY =
            PublicationIdentity.of("blog", "essay", "filesystem-migration");

    @TempDir
    Path reviewRoot;

    @Test
    void filesystemApplySealsGenerationAndGuardAdmitsIt() {
        installApproved();
        LegacyWorkspaceInventoryHandler inventory = inventory();
        MigrationGeneration result = handler(inventory).apply(decision(inventory.inspect()));
        ActivationMarkerStore marker = ActivationMarkerStore.create(reviewRoot);
        marker.save(new ActivationMarker(1, result.inventorySha256(), Instant.parse("2026-08-18T00:00:00Z")));

        assertEquals(MigrationState.SEALED, result.state());
        assertTrue(SchemaActivationGuard.check(
                ApprovedSnapshotWorkspace.create(reviewRoot), CandidateWorkspace.create(reviewRoot), marker,
                MigrationJournalStore.create(reviewRoot), MigrationCatalogStore.create(reviewRoot)).isCurrent());
    }

    @Test
    void filesystemRecoveryRequiresExplicitRollForwardAndSealsRecordedGeneration() {
        installApproved();
        LegacyWorkspaceInventoryHandler inventory = inventory();
        MigrationGeneration running = new MigrationGeneration(
                inventory.inspect().inventorySha256(), List.of(IDENTITY), 0, MigrationState.RUNNING);
        MigrationWorkspace workspace = MigrationWorkspace.create(reviewRoot);
        MigrationJournalStore journal = MigrationJournalStore.create(reviewRoot);
        journal.save(running, workspace.capture(running));

        MigrationGeneration result = handler(inventory).rollForward();

        assertEquals(MigrationState.SEALED, result.state());
        assertEquals(MigrationState.SEALED, MigrationCatalogStore.create(reviewRoot).read().orElseThrow().state());
    }

    @Test
    void markerWriteFailureLeavesSealedStateRecoverableByExplicitRollForward() {
        installApproved();
        LegacyWorkspaceInventoryHandler inventory = inventory();
        FailingMarkerStore marker = new FailingMarkerStore(ActivationMarkerStore.create(reviewRoot));
        MigrationApplyHandler applying = handler(inventory, marker);

        assertThrows(RuntimeException.class, () -> applying.apply(decision(inventory.inspect())));
        assertTrue(MigrationJournalStore.create(reviewRoot).read().orElseThrow().isSealed());

        marker.resume();
        MigrationGeneration recovered = handler(inventory, marker).rollForward();

        assertEquals(MigrationState.SEALED, recovered.state());
        assertTrue(marker.read().isPresent());
    }

    @Test
    void rollbackClearsAPreexistingMarker() {
        installApproved();
        LegacyWorkspaceInventoryHandler inventory = inventory();
        MigrationGeneration running = new MigrationGeneration(
                inventory.inspect().inventorySha256(), List.of(IDENTITY), 0, MigrationState.RUNNING);
        MigrationJournalStore journal = MigrationJournalStore.create(reviewRoot);
        MigrationWorkspace workspace = MigrationWorkspace.create(reviewRoot);
        journal.save(running, workspace.capture(running));
        ActivationMarkerStore marker = ActivationMarkerStore.create(reviewRoot);
        marker.save(new ActivationMarker(1, running.inventorySha256(), Instant.parse("2026-08-18T00:00:00Z")));

        MigrationGeneration rolledBack = handler(inventory, marker).rollBack();

        assertEquals(MigrationState.ROLLED_BACK, rolledBack.state());
        assertTrue(marker.read().isEmpty());
    }

    @Test
    void incompleteMarkerOnlyStateIsRejected() {
        installApproved();
        ActivationMarkerStore marker = ActivationMarkerStore.create(reviewRoot);
        marker.save(new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z")));

        SchemaActivationCheck check = SchemaActivationGuard.check(
                ApprovedSnapshotWorkspace.create(reviewRoot), CandidateWorkspace.create(reviewRoot), marker,
                MigrationJournalStore.create(reviewRoot), MigrationCatalogStore.create(reviewRoot));

        assertTrue(check.isLegacy());
        assertTrue(check.blockingReason().contains("roll forward"));
    }

    @Test
    void emptyGreenfieldWorkspaceRemainsCurrentWithoutMigrationState() {
        assertTrue(SchemaActivationGuard.check(
                ApprovedSnapshotWorkspace.create(reviewRoot), CandidateWorkspace.create(reviewRoot),
                ActivationMarkerStore.create(reviewRoot), MigrationJournalStore.create(reviewRoot),
                MigrationCatalogStore.create(reviewRoot)).isCurrent());
    }

    @Test
    void unsafeCatalogTargetIsRejectedBeforeJournalOrWorkspaceMutation() throws Exception {
        installApproved();
        LegacyWorkspaceInventoryHandler inventory = inventory();
        CandidateSnapshot approvedBefore = ApprovedSnapshotWorkspace.create(reviewRoot)
                .read(IDENTITY).orElseThrow();
        Path migration = Files.createDirectories(reviewRoot.resolve(".migration"));
        Path inRootTarget = reviewRoot.resolve("catalog-target.json");
        Files.writeString(inRootTarget, "target");
        Files.createSymbolicLink(migration.resolve("migration-catalog.json"), Path.of("../catalog-target.json"));

        assertThrows(RuntimeException.class,
                () -> handler(inventory).apply(decision(inventory.inspect())));
        assertTrue(MigrationJournalStore.create(reviewRoot).read().isEmpty());
        assertEquals(approvedBefore,
                ApprovedSnapshotWorkspace.create(reviewRoot).read(IDENTITY).orElseThrow());
    }

    @Test
    void unsafeMarkerTargetIsRejectedBeforeJournalOrWorkspaceMutation() throws Exception {
        installApproved();
        LegacyWorkspaceInventoryHandler inventory = inventory();
        CandidateSnapshot approvedBefore = ApprovedSnapshotWorkspace.create(reviewRoot)
                .read(IDENTITY).orElseThrow();
        Path migration = Files.createDirectories(reviewRoot.resolve(".migration"));
        Path inRootTarget = reviewRoot.resolve("marker-target.json");
        Files.writeString(inRootTarget, "target");
        Files.createSymbolicLink(migration.resolve("schema-v1.active.json"), Path.of("../marker-target.json"));

        assertThrows(RuntimeException.class,
                () -> handler(inventory).apply(decision(inventory.inspect())));
        assertTrue(MigrationJournalStore.create(reviewRoot).read().isEmpty());
        assertEquals(approvedBefore,
                ApprovedSnapshotWorkspace.create(reviewRoot).read(IDENTITY).orElseThrow());
    }

    private LegacyWorkspaceInventoryHandler inventory() {
        return new LegacyWorkspaceInventoryHandler(
                ApprovedSnapshotWorkspace.create(reviewRoot), CandidateWorkspace.create(reviewRoot));
    }

    private MigrationApplyHandler handler(LegacyWorkspaceInventoryHandler inventory) {
        return handler(inventory, ActivationMarkerStore.create(reviewRoot));
    }

    private MigrationApplyHandler handler(LegacyWorkspaceInventoryHandler inventory, ActivationMarkerStore marker) {
        return new MigrationApplyHandler(inventory, new LegacyMigrationDecisionCodec(),
                MigrationJournalStore.create(reviewRoot), MigrationCatalogStore.create(reviewRoot),
                MigrationWorkspace.create(reviewRoot), SemanticOperationLock.create(reviewRoot), marker);
    }

    private void installApproved() {
        ApprovedSnapshotWorkspace.create(reviewRoot).install(IDENTITY, snapshot());
    }

    private static String decision(LegacyWorkspaceInventory inventory) {
        return "{\"schemaVersion\":1,\"inventorySha256\":\""
                + inventory.inventorySha256() + "\"}";
    }

    private static CandidateSnapshot snapshot() {
        String body = "Filesystem legacy body";
        String fields = "[]";
        return CandidateSnapshot.of(body, body, List.of(), List.of(), "",
                ReferenceMap.of(IDENTITY, "filesystem-source-id", ContentHash.sha256Hex(body),
                        ContentHash.sha256Hex(body), ContentHash.sha256Hex(fields),
                ContentHash.sha256Hex(fields), ContentHash.sha256Hex(""), List.of()));
    }

    private static final class FailingMarkerStore implements ActivationMarkerStore {
        private final ActivationMarkerStore delegate;
        private boolean failing = true;

        private FailingMarkerStore(ActivationMarkerStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<ActivationMarker> read() {
            return delegate.read();
        }

        @Override
        public void save(ActivationMarker marker) {
            if (failing) {
                throw new MigrationRecoveryException("Injected marker publication failure.");
            }
            delegate.save(marker);
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        private void resume() {
            failing = false;
        }
    }
}
