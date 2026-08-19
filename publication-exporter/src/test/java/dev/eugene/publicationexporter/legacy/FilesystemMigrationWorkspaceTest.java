package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.hash.ContentHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FilesystemMigrationWorkspaceTest {

    @TempDir
    Path reviewRoot;

    @Test
    void acceptsNonExistingRootWithSystemAliasAncestor() {
        Path notYetCreated = reviewRoot.resolve("review");

        assertDoesNotThrow(() -> FilesystemMigrationPath.safeRoot(notYetCreated));
    }

    @Test
    void secondFilesystemLockCannotEnterWhileFirstIsHeld() {
        SemanticOperationLock first = SemanticOperationLock.create(reviewRoot);
        SemanticOperationLock second = SemanticOperationLock.create(reviewRoot);

        assertThrows(SemanticOperationInProgressException.class,
                () -> first.exclusively(() -> second.exclusively(() -> null)));
    }

    @Test
    void capturesAndRestoresFilesystemSnapshots() {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        CandidateSnapshot snapshot = CandidateSnapshot.of("ru", "en", List.of(), List.of(), "{}",
                ReferenceMap.empty(identity, ContentHash.sha256Hex("ru"), ContentHash.sha256Hex("en"),
                        ContentHash.sha256Hex(PublicFieldsCodec.write(List.of())),
                        ContentHash.sha256Hex(PublicFieldsCodec.write(List.of())), ContentHash.sha256Hex("{}")));
        CandidateWorkspace.create(reviewRoot).install(identity, snapshot, List.of());
        ApprovedSnapshotWorkspace.create(reviewRoot).install(identity, snapshot);

        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);
        MigrationWorkspace workspace = MigrationWorkspace.create(reviewRoot);

        MigrationPreimage preimage = workspace.capture(generation);
        workspace.apply(generation, 0);
        workspace.restore(preimage);
        assertEquals(snapshot, ApprovedSnapshotWorkspace.create(reviewRoot).read(identity).orElseThrow());
    }

    @Test
    void applyingCandidateDoesNotPromoteItToApprovedWorkspace() {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        CandidateSnapshot snapshot = validSnapshot(identity);
        CandidateWorkspace.create(reviewRoot).install(identity, snapshot, List.of());
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);
        MigrationWorkspace.create(reviewRoot).apply(generation, 0);
        assertEquals(java.util.Optional.empty(), ApprovedSnapshotWorkspace.create(reviewRoot).read(identity));
    }

    @Test
    void applyingSharedIdentityDoesNotReplaceApprovalFromCandidate() {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        CandidateSnapshot candidate = validSnapshot(identity, "candidate-ru", "candidate-en");
        CandidateSnapshot approved = validSnapshot(identity, "approved-ru", "approved-en");
        CandidateWorkspace.create(reviewRoot).install(identity, candidate, List.of());
        ApprovedSnapshotWorkspace.create(reviewRoot).install(identity, approved);
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);

        MigrationWorkspace.create(reviewRoot).apply(generation, 0);

        assertEquals(candidate, CandidateWorkspace.create(reviewRoot).read(identity).orElseThrow());
        assertEquals(approved, ApprovedSnapshotWorkspace.create(reviewRoot).read(identity).orElseThrow());
    }

    @Test
    void restoreRemovesCounterpartCreatedByApply() {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        CandidateSnapshot snapshot = validSnapshot(identity);
        CandidateWorkspace.create(reviewRoot).install(identity, snapshot, List.of());
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);
        MigrationWorkspace workspace = MigrationWorkspace.create(reviewRoot);
        MigrationPreimage preimage = workspace.capture(generation);
        workspace.apply(generation, 0);
        workspace.restore(preimage);
        assertEquals(java.util.Optional.empty(), ApprovedSnapshotWorkspace.create(reviewRoot).read(identity));
    }

    @Test
    void captureRejectsSymlinkedAsset() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        CandidateSnapshot snapshot = validSnapshot(identity);
        CandidateWorkspace.create(reviewRoot).install(identity, snapshot,
                List.of(dev.eugene.publicationexporter.candidate.CandidateAsset.of("logo.png", new byte[] {1})));
        Path asset = reviewRoot.resolve("notes/one/candidate/assets/logo.png");
        Path outside = reviewRoot.resolveSibling("asset-outside");
        Files.write(outside, new byte[] {9});
        Files.delete(asset);
        Files.createSymbolicLink(asset, outside);
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);
        assertThrows(MigrationRecoveryException.class, () -> MigrationWorkspace.create(reviewRoot).capture(generation));
    }

    @Test
    void captureRejectsSymlinkedAssetsDirectory() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        CandidateSnapshot snapshot = validSnapshot(identity);
        CandidateWorkspace.create(reviewRoot).install(identity, snapshot,
                List.of(dev.eugene.publicationexporter.candidate.CandidateAsset.of("logo.png", new byte[] {1})));
        Path assets = reviewRoot.resolve("notes/one/candidate/assets");
        Path outside = reviewRoot.resolveSibling("assets-directory-outside");
        Files.createDirectories(outside);
        Files.delete(assets.resolve("logo.png"));
        Files.delete(assets);
        Files.createSymbolicLink(assets, outside);
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);

        assertThrows(MigrationRecoveryException.class,
                () -> MigrationWorkspace.create(reviewRoot).capture(generation));
    }

    @Test
    void captureRejectsInRootSymlinkedCandidateSnapshotFile() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        CandidateWorkspace.create(reviewRoot).install(identity, validSnapshot(identity), List.of());
        Path snapshotFile = reviewRoot.resolve("notes/one/candidate/ru.md");
        Path inRootTarget = reviewRoot.resolve("shared-ru.md");
        Files.writeString(inRootTarget, "ru");
        Files.delete(snapshotFile);
        Files.createSymbolicLink(snapshotFile, snapshotFile.getParent().relativize(inRootTarget));
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);

        assertThrows(MigrationRecoveryException.class,
                () -> MigrationWorkspace.create(reviewRoot).capture(generation));
    }

    @Test
    void captureRejectsEscapingSymlinkedCandidateSnapshotFile() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        CandidateWorkspace.create(reviewRoot).install(identity, validSnapshot(identity), List.of());
        Path snapshotFile = reviewRoot.resolve("notes/one/candidate/en.md");
        Path outside = reviewRoot.resolveSibling("outside-en-" + java.util.UUID.randomUUID() + ".md");
        Files.writeString(outside, "en");
        Files.delete(snapshotFile);
        Files.createSymbolicLink(snapshotFile, outside);
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);

        assertThrows(MigrationRecoveryException.class,
                () -> MigrationWorkspace.create(reviewRoot).capture(generation));
    }

    @Test
    void captureRejectsInRootSymlinkedApprovedSnapshotDirectory() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        ApprovedSnapshotWorkspace.create(reviewRoot).install(identity, validSnapshot(identity));
        Path approved = reviewRoot.resolve("notes/one/approved");
        Path inRootTarget = reviewRoot.resolve("approved-target");
        Files.move(approved, inRootTarget);
        Files.createSymbolicLink(approved, approved.getParent().relativize(inRootTarget));
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);

        assertThrows(MigrationRecoveryException.class,
                () -> MigrationWorkspace.create(reviewRoot).capture(generation));
    }

    @Test
    void captureRejectsEscapingSymlinkedApprovedSnapshotDirectory() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        ApprovedSnapshotWorkspace.create(reviewRoot).install(identity, validSnapshot(identity));
        Path approved = reviewRoot.resolve("notes/one/approved");
        Path outside = reviewRoot.resolveSibling("approved-outside-" + java.util.UUID.randomUUID());
        Files.move(approved, outside);
        Files.createSymbolicLink(approved, outside);
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);

        assertThrows(MigrationRecoveryException.class,
                () -> MigrationWorkspace.create(reviewRoot).capture(generation));
    }

    @Test
    void rootReplacementIsRejectedBeforeReadingJournal() throws Exception {
        FilesystemMigrationJournalStore store = new FilesystemMigrationJournalStore(reviewRoot);
        Path outside = reviewRoot.resolveSibling("replaced-root-outside");
        Files.createDirectories(outside);
        Files.delete(reviewRoot);
        Files.createSymbolicLink(reviewRoot, outside);

        assertThrows(MigrationRecoveryException.class, store::read);
    }

    @Test
    void verifyRejectsChangedCandidateBeforeRollForward() {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        CandidateSnapshot original = validSnapshot(identity);
        CandidateWorkspace candidates = CandidateWorkspace.create(reviewRoot);
        candidates.install(identity, original, List.of());
        ApprovedSnapshotWorkspace.create(reviewRoot).install(identity, original);
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);
        MigrationWorkspace workspace = MigrationWorkspace.create(reviewRoot);
        MigrationPreimage preimage = workspace.capture(generation);
        candidates.install(identity, CandidateSnapshot.of("changed", original.enBody(), original.ruFields(),
                original.enFields(), original.structuredData(), original.referenceMap()), List.of());

        assertThrows(MigrationRecoveryException.class, () -> workspace.verify(generation, preimage));
    }

    @Test
    void verifyAcceptsAnAlreadyAppliedTargetBeforeCursorAdvance() {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        CandidateSnapshot original = validSnapshot(identity);
        CandidateWorkspace candidates = CandidateWorkspace.create(reviewRoot);
        candidates.install(identity, original, List.of());
        ApprovedSnapshotWorkspace.create(reviewRoot).install(identity, original);
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);
        MigrationWorkspace workspace = MigrationWorkspace.create(reviewRoot);
        MigrationPreimage preimage = workspace.capture(generation);
        workspace.apply(generation, 0);

        assertDoesNotThrow(() -> workspace.verify(generation, preimage));
    }

    @Test
    void verifyRejectsCandidatePromotionForCompletedApprovedOnlyIdentity() {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        CandidateSnapshot approved = validSnapshot(identity);
        ApprovedSnapshotWorkspace.create(reviewRoot).install(identity, approved);
        MigrationGeneration started = new MigrationGeneration("a".repeat(64), List.of(identity), 0,
                MigrationState.RUNNING);
        MigrationWorkspace workspace = MigrationWorkspace.create(reviewRoot);
        MigrationPreimage preimage = workspace.capture(started);
        CandidateWorkspace.create(reviewRoot).install(identity, approved, List.of());
        MigrationGeneration completed = started.advance();

        assertThrows(MigrationRecoveryException.class, () -> workspace.verify(completed, preimage));
    }

    @Test
    void configuredSymlinkRootIsRejected() throws Exception {
        Path configured = reviewRoot.resolveSibling("configured-root-" + java.util.UUID.randomUUID());
        Files.createDirectories(configured);
        Path alias = reviewRoot.resolveSibling("configured-alias-" + java.util.UUID.randomUUID());
        Files.createSymbolicLink(alias, configured);
        assertThrows(MigrationRecoveryException.class, () -> MigrationWorkspace.create(alias));
        assertThrows(MigrationRecoveryException.class, () -> SemanticOperationLock.create(alias));
        assertThrows(MigrationRecoveryException.class, () -> MigrationCatalogStore.create(alias));
        assertThrows(MigrationRecoveryException.class, () -> ActivationMarkerStore.create(alias));
    }

    @Test
    void lexicalSymlinkedAncestorBeforeCanonicalizationIsRejected() throws Exception {
        Path real = reviewRoot.resolveSibling("real-parent-" + java.util.UUID.randomUUID());
        Files.createDirectories(real);
        Path link = reviewRoot.resolveSibling("link-parent-" + java.util.UUID.randomUUID());
        Files.createSymbolicLink(link, real);
        assertThrows(MigrationRecoveryException.class,
                () -> MigrationWorkspace.create(link.resolve("review")));
    }

    @Test
    void lateApprovedFailureIsCompensatedAndRetryable() {
        PublicationIdentity identity = PublicationIdentity.of("notes", "article", "one");
        CandidateSnapshot original = validSnapshot(identity);
        CandidateWorkspace.create(reviewRoot).install(identity, original, List.of());
        ApprovedSnapshotWorkspace.create(reviewRoot).install(identity, original);
        MigrationGeneration generation = new MigrationGeneration("a".repeat(64), List.of(identity), 0, MigrationState.RUNNING);
        MigrationPreimage target = new MigrationPreimage(generation, Map.of(identity, validSnapshot(identity)), Map.of(identity, validSnapshot(identity)));
        FailingRestoreOperation operation = new FailingRestoreOperation(reviewRoot);
        FilesystemMigrationWorkspace workspace = new FilesystemMigrationWorkspace(reviewRoot, operation);
        assertThrows(MigrationRecoveryException.class, () -> workspace.restore(target));
        assertEquals(original, CandidateWorkspace.create(reviewRoot).read(identity).orElseThrow());
        assertEquals(original, ApprovedSnapshotWorkspace.create(reviewRoot).read(identity).orElseThrow());
        workspace.restore(target);
    }

    private static final class FailingRestoreOperation implements FilesystemMigrationWorkspace.RestoreOperation {
        private final CandidateWorkspace candidates;
        private final ApprovedSnapshotWorkspace approved;
        private boolean fail = true;
        FailingRestoreOperation(Path root) { candidates = CandidateWorkspace.create(root); approved = ApprovedSnapshotWorkspace.create(root); }
        public void installCandidate(PublicationIdentity id, CandidateSnapshot snapshot, List<dev.eugene.publicationexporter.candidate.CandidateAsset> assets) { candidates.install(id, snapshot, assets); }
        public void installApproved(PublicationIdentity id, CandidateSnapshot snapshot) { if (fail) { fail = false; throw new MigrationRecoveryException("injected late failure"); } approved.install(id, snapshot); }
        public void delete(Path directory) { }
    }

    private static CandidateSnapshot validSnapshot(PublicationIdentity identity) {
        return validSnapshot(identity, "ru", "en");
    }

    private static CandidateSnapshot validSnapshot(PublicationIdentity identity, String ru, String en) {
        return CandidateSnapshot.of(ru, en, List.of(), List.of(), "{}",
                ReferenceMap.empty(identity, ContentHash.sha256Hex(ru), ContentHash.sha256Hex(en),
                        ContentHash.sha256Hex(PublicFieldsCodec.write(List.of())),
                        ContentHash.sha256Hex(PublicFieldsCodec.write(List.of())), ContentHash.sha256Hex("{}")));
    }
}
