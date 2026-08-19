package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationApplyHandlerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "migration");

    @Test
    void completeApplySealsOneGenerationAndRecordsItsCatalog() {
        Fixture fixture = fixture();
        assertEquals(List.of(), fixture.inventory().blockers());
        assertEquals(List.of(), fixture.inventory().ambiguities());

        MigrationGeneration result = fixture.handler.apply(fixture.decisions());

        assertEquals(MigrationState.SEALED, result.state());
        assertEquals(result, fixture.journal.read().orElseThrow());
        assertEquals(result, fixture.catalog.read().orElseThrow());
        assertEquals(1, fixture.workspace.appliedSteps());
    }

    @Test
    void interruptedApplyCanBeRolledBackFromTheRecordedGeneration() {
        Fixture fixture = fixture();
        MigrationGeneration running = new MigrationGeneration(
                fixture.inventory().inventorySha256(), List.of(IDENTITY), 0, MigrationState.RUNNING);
        fixture.journal.save(running, fixture.workspace.configured().orElseThrow());

        MigrationGeneration result = fixture.handler.rollBack();

        assertEquals(MigrationState.ROLLED_BACK, result.state());
        assertEquals(result, fixture.journal.read().orElseThrow());
        assertEquals(1, fixture.workspace.restoredPreimages());
    }

    @Test
    void interruptedApplyCanBeRolledForwardWithoutReadingDecisionsAgain() {
        Fixture fixture = fixture();
        MigrationGeneration running = new MigrationGeneration(
                fixture.inventory().inventorySha256(), List.of(IDENTITY), 0, MigrationState.RUNNING);
        fixture.journal.save(running, fixture.workspace.configured().orElseThrow());

        MigrationGeneration result = fixture.handler.rollForward();

        assertEquals(MigrationState.SEALED, result.state());
        assertEquals(1, fixture.workspace.appliedSteps());
        assertEquals(result, fixture.catalog.read().orElseThrow());
    }

    @Test
    void rollForwardRejectsWorkspaceChangedSinceJournalCapture() {
        Fixture fixture = fixture();
        MigrationGeneration running = new MigrationGeneration(
                fixture.inventory().inventorySha256(), List.of(IDENTITY), 0, MigrationState.RUNNING);
        fixture.journal.save(running, fixture.workspace.configured().orElseThrow());
        fixture.workspace.tamper();

        assertThrows(MigrationRecoveryException.class, fixture.handler::rollForward);
    }

    @Test
    void sealedRollForwardRejectsWorkspaceChangedBeforeMarkerRepublish() {
        Fixture fixture = fixture();
        MigrationGeneration sealed = new MigrationGeneration(
                fixture.inventory().inventorySha256(), List.of(IDENTITY), 1, MigrationState.SEALED);
        fixture.journal.save(sealed, fixture.workspace.configured().orElseThrow());
        fixture.catalog.save(sealed);
        fixture.workspace.tamper();

        assertThrows(MigrationRecoveryException.class, fixture.handler::rollForward);
    }

    @Test
    void preflightRejectsBlockersBeforeCapturingOrJournaling() {
        Fixture fixture = blockedFixture();

        assertThrows(LegacyMigrationDecisionException.class,
                () -> fixture.handler.apply(fixture.decisions()));
        assertEquals(0, fixture.workspace.captures());
        assertEquals(0, fixture.journal.read().stream().count());
        assertEquals(0, fixture.catalog.read().stream().count());
    }

    @Test
    void preflightRejectsAmbiguityBeforeMutation() {
        Fixture fixture = ambiguousFixture();

        assertThrows(LegacyMigrationDecisionException.class,
                () -> fixture.handler.apply(fixture.decisions()));
        assertEquals(0, fixture.workspace.captures());
        assertEquals(0, fixture.journal.read().stream().count());
    }

    @Test
    void applyRefusesToOverwriteAnExistingRunningJournal() {
        Fixture fixture = fixture();
        MigrationGeneration running = new MigrationGeneration(
                fixture.inventory().inventorySha256(), List.of(IDENTITY), 0, MigrationState.RUNNING);
        MigrationPreimage preimage = fixture.workspace.configured().orElseThrow();
        fixture.journal.save(running, preimage);

        assertThrows(MigrationRecoveryException.class,
                () -> fixture.handler.apply(fixture.decisions()));
        assertEquals(preimage, fixture.journal.preimage().orElseThrow());
        assertEquals(0, fixture.workspace.captures());
    }

    @Test
    void interruptionAfterMutationLeavesJournalRecoverableAndReapplyIsIdempotent() {
        Fixture fixture = interruptedFixture();

        assertThrows(MigrationInterruptionException.class,
                () -> fixture.handler.apply(fixture.decisions()));
        MigrationGeneration running = fixture.journal.read().orElseThrow();
        assertEquals(MigrationState.RUNNING, running.state());
        assertEquals(fixture.workspace.captured().orElseThrow(), fixture.journal.preimage().orElseThrow());

        fixture.workspace.resume();
        MigrationGeneration sealed = fixture.handler.rollForward();

        assertEquals(MigrationState.SEALED, sealed.state());
        assertEquals(1, fixture.workspace.appliedSteps());
    }

    @Test
    void interruptedApplyCanRollBackTheExactCapturedCandidateAndApprovedState() {
        Fixture fixture = interruptedFixture();

        assertThrows(MigrationInterruptionException.class,
                () -> fixture.handler.apply(fixture.decisions()));
        MigrationPreimage captured = fixture.journal.preimage().orElseThrow();

        MigrationGeneration rolledBack = fixture.handler.rollBack();

        assertEquals(MigrationState.ROLLED_BACK, rolledBack.state());
        assertEquals(captured, fixture.workspace.restored().orElseThrow());
        assertEquals(1, captured.candidateSnapshots().size());
        assertEquals(1, captured.approvedSnapshots().size());
    }

    @Test
    void duplicateIdentitiesAreRejectedByTheGenerationValue() {
        assertThrows(IllegalArgumentException.class, () -> new MigrationGeneration(
                "a".repeat(64), List.of(IDENTITY, IDENTITY), 0, MigrationState.RUNNING));
    }

    @Test
    void duplicateIdentitiesAreRejectedByTheInventoryBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new LegacyWorkspaceInventory(
                List.of(IDENTITY, IDENTITY), List.of(), List.of(), List.of(), "a".repeat(64)));
    }

    @Test
    void multipleIdentityApplyUsesDeterministicIdentityOrdering() {
        MultiFixture fixture = multipleFixture();

        MigrationGeneration result = fixture.handler.apply(fixture.decisions());

        assertEquals(List.of(fixture.first, fixture.second), result.identities());
    }

    @Test
    void twoIdentityInterruptionAfterCursorAdvanceRollsBackExactPreimage() {
        MultiFixture base = multipleFixture();
        NullMigrationWorkspace workspace = new NullMigrationWorkspace(base.preimage, 1, true);
        MigrationApplyHandler handler = new MigrationApplyHandler(base.inventoryHandler,
                new LegacyMigrationDecisionCodec(), base.journal, base.catalog, workspace,
                new NullSemanticOperationLock());

        assertThrows(MigrationInterruptionException.class, () -> handler.apply(base.decisions()));
        assertEquals(1, base.journal.read().orElseThrow().completedSteps());
        MigrationPreimage recorded = base.journal.preimage().orElseThrow();

        handler.rollBack();

        assertEquals(recorded, workspace.restored().orElseThrow());
    }

    @Test
    void twoIdentityInterruptionAfterCursorAdvanceRollsForwardToSealedGeneration() {
        MultiFixture base = multipleFixture();
        NullMigrationWorkspace workspace = new NullMigrationWorkspace(base.preimage, 1, true);
        MigrationApplyHandler handler = new MigrationApplyHandler(base.inventoryHandler,
                new LegacyMigrationDecisionCodec(), base.journal, base.catalog, workspace,
                new NullSemanticOperationLock());

        assertThrows(MigrationInterruptionException.class, () -> handler.apply(base.decisions()));
        assertEquals(1, base.journal.read().orElseThrow().completedSteps());
        workspace.resume();

        MigrationGeneration sealed = handler.rollForward();

        assertEquals(MigrationState.SEALED, sealed.state());
        assertEquals(base.first, sealed.identities().get(0));
        assertEquals(base.second, sealed.identities().get(1));
        assertEquals(2, sealed.completedSteps());
        assertEquals(2, workspace.appliedSteps());
        assertEquals(sealed, base.journal.read().orElseThrow());
    }

    @Test
    void preimageRemainsAssociatedAcrossCursorAndRollbackAfterPersistedStep() {
        Fixture fixture = fixture();
        MigrationGeneration running = new MigrationGeneration(
                fixture.inventory().inventorySha256(), List.of(IDENTITY), 0, MigrationState.RUNNING);
        MigrationPreimage preimage = fixture.workspace.configured().orElseThrow();
        fixture.journal.save(running, preimage);
        fixture.workspace.apply(running, 0);
        MigrationGeneration advanced = running.advance();
        fixture.journal.save(advanced);

        assertEquals(preimage, fixture.journal.preimage().orElseThrow());
        fixture.handler.rollBack();
        assertEquals(preimage, fixture.workspace.restored().orElseThrow());
    }

    @Test
    void incompletePreimageCoverageIsRejectedBeforeJournalPersistence() {
        MigrationGeneration generation = new MigrationGeneration(
                "b".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING);

        assertThrows(IllegalArgumentException.class,
                () -> new MigrationPreimage(generation, Map.of(), Map.of()));
    }

    @Test
    void approvedOnlyAndCandidateOnlyPreimagesMaySplitCoverage() {
        PublicationIdentity other = PublicationIdentity.of("blog", "essay", "other");
        MigrationGeneration generation = new MigrationGeneration(
                "d".repeat(64), List.of(IDENTITY, other), 0, MigrationState.RUNNING);

        MigrationPreimage preimage = new MigrationPreimage(generation,
                Map.of(IDENTITY, CandidateSnapshot.of("ru", "en", List.of(), List.of(), "",
                        reference(IDENTITY, "candidate-only"))),
                Map.of(other, CandidateSnapshot.of("ru", "en", List.of(), List.of(), "",
                        reference(other, "approved-only"))));

        assertEquals(1, preimage.candidateSnapshots().size());
        assertEquals(1, preimage.approvedSnapshots().size());
    }

    @Test
    void candidateAssetsCannotBelongToAnApprovedOnlyIdentity() {
        MigrationGeneration generation = new MigrationGeneration(
                "d".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING);
        CandidateSnapshot approved = CandidateSnapshot.of("ru", "en", List.of(), List.of(), "",
                reference(IDENTITY, "approved-only"));

        assertThrows(IllegalArgumentException.class, () -> new MigrationPreimage(
                generation, Map.of(), Map.of(IDENTITY, approved),
                Map.of(IDENTITY, List.of(
                        dev.eugene.publicationexporter.candidate.CandidateAsset.of("asset.png", new byte[] {1})))));
    }

    @Test
    void nullWorkspaceRejectsACompletedCursorWhoseStepWasNotApplied() {
        MigrationGeneration started = new MigrationGeneration(
                "d".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING);
        MigrationPreimage preimage = new MigrationPreimage(started,
                Map.of(IDENTITY, CandidateSnapshot.of("ru", "en", List.of(), List.of(), "",
                        reference(IDENTITY, "candidate-only"))), Map.of());
        NullMigrationWorkspace workspace = new NullMigrationWorkspace(preimage);
        MigrationPreimage captured = workspace.capture(started);

        assertThrows(MigrationRecoveryException.class,
                () -> workspace.verify(started.advance(), captured));
    }

    @Test
    void defaultNullWorkspaceCapturesAValidPreimageForEveryIdentity() {
        PublicationIdentity other = PublicationIdentity.of("blog", "essay", "other");
        MigrationGeneration generation = new MigrationGeneration(
                "e".repeat(64), List.of(IDENTITY, other), 0, MigrationState.RUNNING);

        MigrationPreimage captured = new NullMigrationWorkspace().capture(generation);

        assertEquals(Set.of(IDENTITY, other), captured.candidateSnapshots().keySet());
        assertEquals(Set.of(IDENTITY, other), captured.approvedSnapshots().keySet());
    }

    @Test
    void sequentialGenerationsCanEachApplyTheirFirstStep() {
        NullMigrationWorkspace workspace = new NullMigrationWorkspace();
        MigrationGeneration first = new MigrationGeneration(
                "f".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING);
        MigrationGeneration second = new MigrationGeneration(
                "0".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING);

        workspace.capture(first);
        workspace.apply(first, 0);
        workspace.capture(second);
        workspace.apply(second, 0);

        assertEquals(2, workspace.appliedApplications());
    }

    @Test
    void mismatchedPreimageSnapshotIdentityIsRejected() {
        MigrationGeneration generation = new MigrationGeneration(
                "c".repeat(64), List.of(IDENTITY), 0, MigrationState.RUNNING);
        PublicationIdentity other = PublicationIdentity.of("blog", "essay", "other");
        CandidateSnapshot snapshot = CandidateSnapshot.of("ru", "en", List.of(), List.of(), "",
                reference(other, "other-source"));

        assertThrows(IllegalArgumentException.class, () -> new MigrationPreimage(
                generation, Map.of(IDENTITY, snapshot), Map.of(IDENTITY, snapshot)));
    }

    @Test
    void lockCollisionStopsApplyBeforeAnyStateChanges() {
        Fixture fixture = fixture();
        fixture.lock.lock();

        assertThrows(SemanticOperationInProgressException.class,
                () -> fixture.handler.apply(fixture.decisions()));
        assertEquals(0, fixture.workspace.captures());
        assertEquals(0, fixture.journal.read().stream().count());
    }

    @Test
    void nullLockRejectsAConcurrentOperation() throws Exception {
        NullSemanticOperationLock lock = new NullSemanticOperationLock();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                lock.exclusively(() -> {
                    entered.countDown();
                    await(release);
                    return null;
                });
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        });
        first.start();
        entered.await();
        assertThrows(SemanticOperationInProgressException.class, () -> lock.exclusively(() -> null));
        release.countDown();
        first.join();
        assertEquals(null, firstFailure.get());
    }

    private static Fixture fixture() {
        CandidateWorkspace candidate = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        ReferenceMap reference = reference(IDENTITY, "source-migration");
        CandidateSnapshot snapshot = CandidateSnapshot.of("ru", "en", List.of(), List.of(), "", reference);
        candidate.install(IDENTITY, snapshot, List.of());
        approved.install(IDENTITY, snapshot);
        LegacyWorkspaceInventoryHandler inventoryHandler =
                new LegacyWorkspaceInventoryHandler(approved, candidate);
        LegacyWorkspaceInventory inventory = inventoryHandler.inspect();
        MigrationGeneration planned = new MigrationGeneration(
                inventory.inventorySha256(), List.of(IDENTITY), 0, MigrationState.RUNNING);
        MigrationPreimage actual = new MigrationPreimage(planned,
                Map.of(IDENTITY, candidate.read(IDENTITY).orElseThrow()),
                Map.of(IDENTITY, approved.read(IDENTITY).orElseThrow()));
        NullMigrationJournalStore journal = new NullMigrationJournalStore();
        NullMigrationCatalogStore catalog = new NullMigrationCatalogStore();
        NullMigrationWorkspace workspace = new NullMigrationWorkspace(actual);
        NullSemanticOperationLock lock = new NullSemanticOperationLock();
        MigrationApplyHandler handler = new MigrationApplyHandler(inventoryHandler, new LegacyMigrationDecisionCodec(),
                journal, catalog, workspace, lock);
        return new Fixture(inventoryHandler, inventory, handler, journal, catalog, workspace, lock);
    }

    private static Fixture interruptedFixture() {
        Fixture base = fixture();
        NullMigrationWorkspace workspace = new NullMigrationWorkspace(
                base.workspace.configured().orElseThrow(), 0, true);
        MigrationApplyHandler handler = new MigrationApplyHandler(
                base.inventoryHandler, new LegacyMigrationDecisionCodec(), base.journal, base.catalog, workspace, base.lock);
        return new Fixture(base.inventoryHandler, base.inventory, handler, base.journal, base.catalog, workspace, base.lock);
    }

    private static MultiFixture multipleFixture() {
        PublicationIdentity first = PublicationIdentity.of("blog", "essay", "a");
        PublicationIdentity second = PublicationIdentity.of("blog", "essay", "z");
        CandidateWorkspace candidate = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        CandidateSnapshot secondSnapshot = CandidateSnapshot.of("ru", "en", List.of(), List.of(), "",
                reference(second, "second-source"));
        CandidateSnapshot firstSnapshot = CandidateSnapshot.of("ru", "en", List.of(), List.of(), "",
                reference(first, "first-source"));
        candidate.install(second, secondSnapshot, List.of());
        candidate.install(first, firstSnapshot, List.of());
        approved.install(second, secondSnapshot);
        approved.install(first, firstSnapshot);
        LegacyWorkspaceInventoryHandler inventoryHandler =
                new LegacyWorkspaceInventoryHandler(approved, candidate);
        LegacyWorkspaceInventory inventory = inventoryHandler.inspect();
        MigrationGeneration planned = new MigrationGeneration(inventory.inventorySha256(),
                List.of(first, second), 0, MigrationState.RUNNING);
        MigrationPreimage preimage = new MigrationPreimage(planned,
                Map.of(first, candidate.read(first).orElseThrow(), second, candidate.read(second).orElseThrow()),
                Map.of(first, approved.read(first).orElseThrow(), second, approved.read(second).orElseThrow()));
        NullMigrationWorkspace workspace = new NullMigrationWorkspace(preimage);
        NullMigrationJournalStore journal = new NullMigrationJournalStore();
        NullMigrationCatalogStore catalog = new NullMigrationCatalogStore();
        MigrationApplyHandler handler = new MigrationApplyHandler(inventoryHandler,
                new LegacyMigrationDecisionCodec(), journal, catalog, workspace, new NullSemanticOperationLock());
        return new MultiFixture(first, second, inventory, inventoryHandler, handler, journal, catalog, preimage);
    }

    private static ReferenceMap reference(PublicationIdentity identity, String sourceId) {
        return ReferenceMap.of(identity, sourceId, ContentHash.sha256Hex("ru"), ContentHash.sha256Hex("en"),
                ContentHash.sha256Hex(dev.eugene.publicationexporter.reference.PublicFieldsCodec.write(List.of())),
                ContentHash.sha256Hex(dev.eugene.publicationexporter.reference.PublicFieldsCodec.write(List.of())),
                ContentHash.sha256Hex(""), List.of());
    }

    private static Fixture blockedFixture() {
        CandidateWorkspace candidate = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        approved.install(IDENTITY, CandidateSnapshot.of("ru", "en", List.of(), List.of(), "",
                ReferenceMap.empty(IDENTITY, ContentHash.sha256Hex("ru"), ContentHash.sha256Hex("en"),
                        ContentHash.sha256Hex(dev.eugene.publicationexporter.reference.PublicFieldsCodec.write(List.of())),
                        ContentHash.sha256Hex(dev.eugene.publicationexporter.reference.PublicFieldsCodec.write(List.of())),
                        ContentHash.sha256Hex(""))));
        LegacyWorkspaceInventoryHandler inventoryHandler =
                new LegacyWorkspaceInventoryHandler(approved, candidate);
        NullMigrationJournalStore journal = new NullMigrationJournalStore();
        NullMigrationCatalogStore catalog = new NullMigrationCatalogStore();
        NullMigrationWorkspace workspace = new NullMigrationWorkspace();
        NullSemanticOperationLock lock = new NullSemanticOperationLock();
        MigrationApplyHandler handler = new MigrationApplyHandler(inventoryHandler, new LegacyMigrationDecisionCodec(),
                journal, catalog, workspace, lock);
        return new Fixture(inventoryHandler, inventoryHandler.inspect(), handler, journal, catalog, workspace, lock);
    }

    private static Fixture ambiguousFixture() {
        CandidateWorkspace candidate = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        candidate.install(IDENTITY, CandidateSnapshot.of("ru", "en", List.of(), List.of(), "",
                reference(IDENTITY, "candidate-source")), List.of());
        approved.install(IDENTITY, CandidateSnapshot.of("ru", "en", List.of(), List.of(), "",
                reference(IDENTITY, "approved-source")));
        LegacyWorkspaceInventoryHandler inventoryHandler =
                new LegacyWorkspaceInventoryHandler(approved, candidate);
        NullMigrationJournalStore journal = new NullMigrationJournalStore();
        NullMigrationCatalogStore catalog = new NullMigrationCatalogStore();
        NullMigrationWorkspace workspace = new NullMigrationWorkspace();
        NullSemanticOperationLock lock = new NullSemanticOperationLock();
        MigrationApplyHandler handler = new MigrationApplyHandler(inventoryHandler, new LegacyMigrationDecisionCodec(),
                journal, catalog, workspace, lock);
        return new Fixture(inventoryHandler, inventoryHandler.inspect(), handler, journal, catalog, workspace, lock);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private record Fixture(LegacyWorkspaceInventoryHandler inventoryHandler, LegacyWorkspaceInventory inventory,
            MigrationApplyHandler handler,
            NullMigrationJournalStore journal, NullMigrationCatalogStore catalog,
            NullMigrationWorkspace workspace, NullSemanticOperationLock lock) {
        private String decisions() {
            return "{\"schemaVersion\":1,\"inventorySha256\":\"" + inventory.inventorySha256() + "\"}";
        }
    }

    private record MultiFixture(PublicationIdentity first, PublicationIdentity second,
            LegacyWorkspaceInventory inventory, LegacyWorkspaceInventoryHandler inventoryHandler,
            MigrationApplyHandler handler, NullMigrationJournalStore journal,
            NullMigrationCatalogStore catalog, MigrationPreimage preimage) {
        private String decisions() {
            return "{\"schemaVersion\":1,\"inventorySha256\":\"" + inventory.inventorySha256() + "\"}";
        }
    }
}
