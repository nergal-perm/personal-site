package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemApprovedSnapshotWorkspaceTest {

    @TempDir
    Path reviewRoot;

    @TempDir
    Path outsideRoot;

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void installWritesAllSnapshotFilesAtTheirFinalPath() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash");

        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        assertEquals("RU body", Files.readString(approvedDir.resolve("ru.md")));
        assertEquals("EN body", Files.readString(approvedDir.resolve("en.md")));
        assertEquals("RU title", Files.readString(approvedDir.resolve("ru.title")));
        assertEquals("EN title", Files.readString(approvedDir.resolve("en.title")));
        assertEquals("RU description.", Files.readString(approvedDir.resolve("ru.description")));
        assertEquals("EN description.", Files.readString(approvedDir.resolve("en.description")));
        assertTrue(Files.readString(approvedDir.resolve("references.json")).contains("\"ruHash\":\"ru-hash\""));
    }

    @Test
    void findIsAbsentBeforeInstall() {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }

    @Test
    void findReturnsAbsolutePathsToTheInstalledFiles() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", matchingReferenceMap(
                        "RU body", "EN body", "RU title", "EN title",
                        "RU description.", "EN description."));

        Optional<CandidatePaths> found = workspace.find(IDENTITY);

        assertTrue(found.isPresent());
        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        assertEquals(approvedDir.resolve("ru.md").toRealPath(), found.get().ruPath().toRealPath());
        assertTrue(found.get().ruPath().isAbsolute());
    }

    @Test
    void readIsAbsentBeforeInstall() {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void readReturnsTheInstalledBodiesAndReferenceMap() {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        ReferenceMap referenceMap = matchingReferenceMap(
                "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.");
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Optional<dev.eugene.publicationexporter.candidate.CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU body", read.get().ruBody());
        assertEquals("EN body", read.get().enBody());
        assertEquals("RU title", read.get().ruTitle());
        assertEquals("EN title", read.get().enTitle());
        assertEquals("RU description.", read.get().ruDescription());
        assertEquals("EN description.", read.get().enDescription());
        assertEquals(referenceMap, read.get().referenceMap());
    }

    @Test
    void readRetriesWhenApprovedDirectoryGenerationChangesMidRead() throws Exception {
        installSnapshot(new FilesystemApprovedSnapshotWorkspace(reviewRoot), "Old");
        CountDownLatch oldRussianBodyRead = new CountDownLatch(1);
        CountDownLatch replacementCompleted = new CountDownLatch(1);
        AtomicBoolean pauseOnce = new AtomicBoolean();
        FilesystemApprovedSnapshotWorkspace reader = new FilesystemApprovedSnapshotWorkspace(
                reviewRoot,
                (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE),
                file -> {
                    if (file.getFileName().toString().equals("ru.md")
                            && pauseOnce.compareAndSet(false, true)) {
                        oldRussianBodyRead.countDown();
                        try {
                            if (!replacementCompleted.await(5, TimeUnit.SECONDS)) {
                                throw new java.io.IOException("replacement did not complete");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException(interrupted);
                        }
                    }
                });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var read = executor.submit(() -> reader.read(IDENTITY).orElseThrow());
            assertTrue(oldRussianBodyRead.await(5, TimeUnit.SECONDS));

            installSnapshot(new FilesystemApprovedSnapshotWorkspace(reviewRoot), "New");
            replacementCompleted.countDown();
            CandidateSnapshot snapshot = read.get(5, TimeUnit.SECONDS);

            assertEquals("New RU", snapshot.ruBody());
            assertEquals("New EN", snapshot.enBody());
            assertEquals("New RU title", snapshot.ruTitle());
            assertEquals("New EN title", snapshot.enTitle());
            assertEquals("New RU description", snapshot.ruDescription());
            assertEquals("New EN description", snapshot.enDescription());
            assertEquals(referenceMapFor("New"), snapshot.referenceMap());
        } finally {
            replacementCompleted.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void liveAdvisoryLockPreventsAnotherApprovalAttempt() throws Exception {
        FilesystemApprovedSnapshotWorkspace contender = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        Path lockFile = reviewRoot.resolve("blog/my-essay/.mark-reviewed.lock");
        Files.createDirectories(lockFile.getParent());
        AtomicBoolean contenderEntered = new AtomicBoolean();

        try (FileChannel ownerChannel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = ownerChannel.tryLock()) {
            assertThrows(ApprovedSnapshotApprovalInProgressException.class,
                    () -> contender.withApprovalLock(IDENTITY, () -> {
                        contenderEntered.set(true);
                        return null;
                    }));
            assertFalse(contenderEntered.get());
        }
    }

    @Test
    void releasedAdvisoryLockAllowsFreshInstanceToRecoverInterruptedReplace() throws Exception {
        FilesystemApprovedSnapshotWorkspace original = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        installSnapshot(original, "Old");
        Path approvedDir = reviewRoot.resolve("blog/my-essay/approved");
        Path backupDir = approvedDir.resolveSibling("approved-backup-" + java.util.UUID.randomUUID());
        Files.move(approvedDir, backupDir, StandardCopyOption.ATOMIC_MOVE);
        Path lockFile = approvedDir.resolveSibling(".mark-reviewed.lock");
        try (FileChannel ownerChannel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = ownerChannel.tryLock()) {
            assertTrue(ignored.isValid());
        }

        FilesystemApprovedSnapshotWorkspace freshInstance = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        ApprovedSnapshotRecoveryException recovery = assertThrows(
                ApprovedSnapshotRecoveryException.class, () -> freshInstance.read(IDENTITY));
        assertTrue(recovery.getMessage().contains("restored valid backup"));
        assertTrue(Files.exists(lockFile));
        assertEquals("Old RU", freshInstance.read(IDENTITY).orElseThrow().ruBody());
    }

    @Test
    void secondInstanceCannotInstallWhileFirstInstanceIsMidReplace() throws Exception {
        installSnapshot(new FilesystemApprovedSnapshotWorkspace(reviewRoot), "Old");
        CountDownLatch oldSnapshotMovedToBackup = new CountDownLatch(1);
        CountDownLatch finishFirstInstall = new CountDownLatch(1);
        AtomicInteger moves = new AtomicInteger();
        FilesystemApprovedSnapshotWorkspace first = new FilesystemApprovedSnapshotWorkspace(
                reviewRoot, (source, target) -> {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                    if (moves.incrementAndGet() == 1) {
                        oldSnapshotMovedToBackup.countDown();
                        try {
                            if (!finishFirstInstall.await(5, TimeUnit.SECONDS)) {
                                throw new java.io.IOException("first install was not released");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException(interrupted);
                        }
                    }
                });
        FilesystemApprovedSnapshotWorkspace second = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstInstall = executor.submit(() -> installSnapshot(first, "First"));
            assertTrue(oldSnapshotMovedToBackup.await(5, TimeUnit.SECONDS));

            assertThrows(ApprovedSnapshotApprovalInProgressException.class,
                    () -> installSnapshot(second, "Second"));
            try (var entries = Files.list(reviewRoot.resolve("blog/my-essay"))) {
                assertEquals(1, entries.filter(path -> validBackupDirectoryName(path.getFileName().toString())).count());
            }

            finishFirstInstall.countDown();
            firstInstall.get(5, TimeUnit.SECONDS);
            assertEquals("First RU", first.read(IDENTITY).orElseThrow().ruBody());
        } finally {
            finishFirstInstall.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void readFailsTypedAfterFiveDirectoryGenerationChanges() {
        installSnapshot(new FilesystemApprovedSnapshotWorkspace(reviewRoot), "Initial");
        AtomicInteger replacements = new AtomicInteger();
        FilesystemApprovedSnapshotWorkspace writer = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        FilesystemApprovedSnapshotWorkspace reader = new FilesystemApprovedSnapshotWorkspace(
                reviewRoot,
                (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE),
                file -> {
                    if (file.getFileName().toString().equals("ru.md")) {
                        installSnapshot(writer, "Replacement" + replacements.incrementAndGet());
                    }
                });

        ApprovedSnapshotWorkspaceStabilizationException failure = assertThrows(
                ApprovedSnapshotWorkspaceStabilizationException.class, () -> reader.read(IDENTITY));

        assertEquals(5, replacements.get());
        assertTrue(failure.getMessage().contains("after 5 attempts"));
    }

    @Test
    void readRejectsSymlinkedMemberFileEscapingReviewRoot() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", matchingReferenceMap(
                        "RU body", "EN body", "RU title", "EN title",
                        "RU description.", "EN description."));
        Path enPath = reviewRoot.resolve("blog/my-essay/approved/en.md");
        Path outsideEnPath = outsideRoot.resolve("outside-en.md");
        Files.writeString(outsideEnPath, "outside EN body");
        Files.delete(enPath);
        Files.createSymbolicLink(enPath, outsideEnPath);

        assertThrows(ApprovedSnapshotWorkspaceConfinementException.class, () -> workspace.read(IDENTITY));
    }

    @Test
    void readRejectsAMismatchingReferenceMapIdentity() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", matchingReferenceMap(
                        "RU body", "EN body", "RU title", "EN title",
                        "RU description.", "EN description."));

        Path referencesPath = reviewRoot.resolve("blog/my-essay/approved/references.json");
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");
        ReferenceMap mismatching = ReferenceMap.empty(otherIdentity,
                ContentHash.sha256Hex("RU body"), ContentHash.sha256Hex("EN body"),
                ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                ContentHash.sha256Hex("RU description."), ContentHash.sha256Hex("EN description."));
        Files.writeString(referencesPath, ReferenceMapCodec.write(mismatching), StandardCharsets.UTF_8);

        ApprovedSnapshotIntegrityException failure = assertThrows(
                ApprovedSnapshotIntegrityException.class, () -> workspace.read(IDENTITY));
        assertTrue(failure.getMessage().contains("identity does not match"));
    }

    @Test
    void aSecondInstallForTheSameIdentityReplacesTheFirstSnapshot() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", matchingReferenceMap(
                        "RU body", "EN body", "RU title", "EN title",
                        "RU description.", "EN description."));

        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        workspace.install(IDENTITY, "RU body 2", "EN body 2", "RU title 2", "EN title 2",
                "RU description 2.", "EN description 2.", matchingReferenceMap(
                        "RU body 2", "EN body 2", "RU title 2", "EN title 2",
                        "RU description 2.", "EN description 2."));

        assertEquals("RU body 2", Files.readString(approvedDir.resolve("ru.md")));
        assertEquals("EN body 2", Files.readString(approvedDir.resolve("en.md")));
        assertEquals("RU title 2", Files.readString(approvedDir.resolve("ru.title")));
        assertEquals("EN title 2", Files.readString(approvedDir.resolve("en.title")));
        assertEquals("RU description 2.", Files.readString(approvedDir.resolve("ru.description")));
        assertEquals("EN description 2.", Files.readString(approvedDir.resolve("en.description")));
    }

    @Test
    void failedNewMoveRestoresFullyReadableOldApprovedSnapshot() throws Exception {
        new FilesystemApprovedSnapshotWorkspace(reviewRoot).install(
                IDENTITY, "Old RU", "Old EN", "Old RU title", "Old EN title",
                "Old RU description", "Old EN description", referenceMap("old"));
        AtomicInteger moves = new AtomicInteger();
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot,
                (source, target) -> {
                    if (moves.incrementAndGet() == 2) {
                        throw new java.io.IOException("injected failure before new approved snapshot move");
                    }
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                });

        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> workspace.install(IDENTITY, "New RU", "New EN", "New RU title", "New EN title",
                        "New RU description", "New EN description", referenceMap("new")));

        assertTrue(failure.getMessage().contains("injected failure"));
        dev.eugene.publicationexporter.candidate.CandidateSnapshot restored = workspace.read(IDENTITY).orElseThrow();
        assertEquals("Old RU", restored.ruBody());
        assertEquals("Old EN", restored.enBody());
    }

    @Test
    void freshInstanceRecoversFromInterruptedReplaceByRestoringBackup() throws Exception {
        FilesystemApprovedSnapshotWorkspace original = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        original.install(IDENTITY, "Old RU", "Old EN", "Old RU title", "Old EN title",
                "Old RU description", "Old EN description", referenceMap("old"));

        Path approvedDir = reviewRoot.resolve(IDENTITY.publicCollection()).resolve(IDENTITY.publicId())
                .resolve("approved");
        Path backupDir = approvedDir.resolveSibling("approved-backup-" + java.util.UUID.randomUUID());
        Files.move(approvedDir, backupDir, StandardCopyOption.ATOMIC_MOVE);

        FilesystemApprovedSnapshotWorkspace freshInstance = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        ApprovedSnapshotRecoveryException recovery = assertThrows(
                ApprovedSnapshotRecoveryException.class, () -> freshInstance.read(IDENTITY));
        assertTrue(recovery.getMessage().contains("restored valid backup"));
        CandidateSnapshot recovered = freshInstance.read(IDENTITY).orElseThrow();

        assertEquals("Old RU", recovered.ruBody());
        assertEquals("Old EN", recovered.enBody());
        assertTrue(Files.notExists(backupDir), "stale backup should be cleaned up by recovery");
    }

    @Test
    void freshInstanceRejectsAnIncompleteBackupAsUnrecoverable() throws Exception {
        FilesystemApprovedSnapshotWorkspace original = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        original.install(IDENTITY, "Old RU", "Old EN", "Old RU title", "Old EN title",
                "Old RU description", "Old EN description", referenceMap("old"));

        Path approvedDir = reviewRoot.resolve(IDENTITY.publicCollection()).resolve(IDENTITY.publicId())
                .resolve("approved");
        Path backupDir = approvedDir.resolveSibling("approved-backup-" + java.util.UUID.randomUUID());
        Files.move(approvedDir, backupDir, StandardCopyOption.ATOMIC_MOVE);
        Files.delete(backupDir.resolve("en.md"));
        assertFalse(Files.exists(approvedDir));

        FilesystemApprovedSnapshotWorkspace freshInstance = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        ApprovedSnapshotIntegrityException failure = assertThrows(ApprovedSnapshotIntegrityException.class,
                () -> freshInstance.read(IDENTITY));

        assertTrue(failure.getMessage().contains("unrecoverable"));
        assertTrue(failure.getMessage().contains(approvedDir.toString()));
        assertTrue(failure.getMessage().contains(backupDir.toString()));
    }

    @Test
    void freshInstanceKeepsCompleteNewSnapshotAndCleansStaleBackup() throws Exception {
        FilesystemApprovedSnapshotWorkspace original = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        original.install(IDENTITY, "Old RU", "Old EN", "Old RU title", "Old EN title",
                "Old RU description", "Old EN description", referenceMap("old"));

        Path approvedDir = reviewRoot.resolve(IDENTITY.publicCollection()).resolve(IDENTITY.publicId())
                .resolve("approved");
        Path backupDir = approvedDir.resolveSibling("approved-backup-" + java.util.UUID.randomUUID());
        Files.move(approvedDir, backupDir, StandardCopyOption.ATOMIC_MOVE);
        ReferenceMap newReferenceMap = referenceMap("new");
        Files.createDirectories(approvedDir);
        Files.writeString(approvedDir.resolve("ru.md"), "New RU", StandardCharsets.UTF_8);
        Files.writeString(approvedDir.resolve("en.md"), "New EN", StandardCharsets.UTF_8);
        Files.writeString(approvedDir.resolve("ru.title"), "New RU title", StandardCharsets.UTF_8);
        Files.writeString(approvedDir.resolve("en.title"), "New EN title", StandardCharsets.UTF_8);
        Files.writeString(approvedDir.resolve("ru.description"), "New RU description", StandardCharsets.UTF_8);
        Files.writeString(approvedDir.resolve("en.description"), "New EN description", StandardCharsets.UTF_8);
        Files.writeString(approvedDir.resolve("references.json"), ReferenceMapCodec.write(newReferenceMap),
                StandardCharsets.UTF_8);

        FilesystemApprovedSnapshotWorkspace freshInstance = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        ApprovedSnapshotRecoveryException recovery = assertThrows(
                ApprovedSnapshotRecoveryException.class, () -> freshInstance.read(IDENTITY));
        assertTrue(recovery.getMessage().contains("kept the valid canonical snapshot"));
        CandidateSnapshot recovered = freshInstance.read(IDENTITY).orElseThrow();

        assertEquals("New RU", recovered.ruBody());
        assertEquals("New EN", recovered.enBody());
        assertEquals(newReferenceMap, recovered.referenceMap());
        assertTrue(Files.notExists(backupDir), "stale backup should be cleaned up by recovery");
    }

    @Test
    void corruptedCanonicalSnapshotIsReplacedByValidBackupAndReported() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        installSnapshot(workspace, "Old");
        Path approvedDir = reviewRoot.resolve("blog/my-essay/approved");
        Path backupDir = approvedDir.resolveSibling("approved-backup-" + java.util.UUID.randomUUID());
        Files.move(approvedDir, backupDir, StandardCopyOption.ATOMIC_MOVE);
        writeSnapshotDirectory(approvedDir, "New");
        Files.writeString(approvedDir.resolve("ru.md"), "tampered RU", StandardCharsets.UTF_8);

        ApprovedSnapshotRecoveryException recovery = assertThrows(
                ApprovedSnapshotRecoveryException.class, () -> workspace.read(IDENTITY));

        assertTrue(recovery.getMessage().contains("integrity"));
        CandidateSnapshot restored = workspace.read(IDENTITY).orElseThrow();
        assertEquals("Old RU", restored.ruBody());
        assertEquals("Old EN", restored.enBody());
        assertEquals(referenceMapFor("Old"), restored.referenceMap());
        assertTrue(Files.notExists(backupDir));
        assertEquals("Old RU", Files.readString(approvedDir.resolve("ru.md")));
    }

    @Test
    void manualBackupDecoyIsIgnoredAndPreserved() throws Exception {
        installSnapshot(new FilesystemApprovedSnapshotWorkspace(reviewRoot), "Valid");
        Path decoy = reviewRoot.resolve("blog/my-essay/approved-backup-manual");
        Files.createDirectories(decoy);
        Files.writeString(decoy.resolve("operator-note.txt"), "keep me", StandardCharsets.UTF_8);

        CandidateSnapshot snapshot = new FilesystemApprovedSnapshotWorkspace(reviewRoot)
                .read(IDENTITY).orElseThrow();

        assertEquals("Valid RU", snapshot.ruBody());
        assertEquals("keep me", Files.readString(decoy.resolve("operator-note.txt")));
    }

    @Test
    void multipleUuidBackupsFailWithoutDeletingEither() throws Exception {
        installSnapshot(new FilesystemApprovedSnapshotWorkspace(reviewRoot), "Canonical");
        Path identityDirectory = reviewRoot.resolve("blog/my-essay");
        Path firstBackup = identityDirectory.resolve("approved-backup-" + java.util.UUID.randomUUID());
        Path secondBackup = identityDirectory.resolve("approved-backup-" + java.util.UUID.randomUUID());
        writeSnapshotDirectory(firstBackup, "First");
        writeSnapshotDirectory(secondBackup, "Second");

        ApprovedSnapshotIntegrityException failure = assertThrows(
                ApprovedSnapshotIntegrityException.class,
                () -> new FilesystemApprovedSnapshotWorkspace(reviewRoot).read(IDENTITY));

        assertTrue(failure.getMessage().contains("multiple recovery backups"));
        assertTrue(Files.isDirectory(firstBackup));
        assertTrue(Files.isDirectory(secondBackup));
    }

    @Test
    void noStagingDirectoryIsLeftBehindAfterASuccessfulInstall() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        workspace.install(IDENTITY, "RU", "EN", "RU title", "EN title", "RU description.",
                "EN description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));

        try (var entries = Files.list(reviewRoot)) {
            long stagingLeftovers = entries
                    .filter(path -> path.getFileName().toString().startsWith("approved-staging-"))
                    .count();
            assertEquals(0, stagingLeftovers);
        }
    }

    @Test
    void installRejectsCollectionParentSegmentBeforeAnyWrite() {
        Path freshRoot = reviewRoot.resolve("fresh-review-root");
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(freshRoot);
        PublicationIdentity escapingIdentity = PublicationIdentity.of("..", "essay", "escaped-collection");

        assertThrows(IllegalStateException.class,
                () -> workspace.install(escapingIdentity, "RU", "EN", "RU title", "EN title",
                        "RU description.", "EN description.",
                        ReferenceMap.empty(escapingIdentity, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")));

        assertTrue(Files.notExists(freshRoot));
    }

    @Test
    void installRejectsSymlinkedChildEscapingReviewRoot() throws Exception {
        Files.createSymbolicLink(reviewRoot.resolve("blog"), outsideRoot);
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        assertThrows(IllegalStateException.class,
                () -> workspace.install(IDENTITY, "RU", "EN", "RU title", "EN title",
                        "RU description.", "EN description.",
                        ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")));

        assertTrue(Files.notExists(outsideRoot.resolve("my-essay/approved")));
        try (var entries = Files.list(reviewRoot)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith("approved-staging-")));
        }
    }

    private static ReferenceMap referenceMap(String suffix) {
        String generation = Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
        return referenceMapFor(generation);
    }

    private static void installSnapshot(FilesystemApprovedSnapshotWorkspace workspace, String generation) {
        workspace.install(IDENTITY,
                generation + " RU", generation + " EN",
                generation + " RU title", generation + " EN title",
                generation + " RU description", generation + " EN description",
                referenceMapFor(generation));
    }

    private static void writeSnapshotDirectory(Path directory, String generation) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("ru.md"), generation + " RU", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("en.md"), generation + " EN", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("ru.title"), generation + " RU title", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("en.title"), generation + " EN title", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("ru.description"), generation + " RU description", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("en.description"), generation + " EN description", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("references.json"),
                ReferenceMapCodec.write(referenceMapFor(generation)), StandardCharsets.UTF_8);
    }

    private static ReferenceMap referenceMapFor(String generation) {
        return matchingReferenceMap(
                generation + " RU", generation + " EN",
                generation + " RU title", generation + " EN title",
                generation + " RU description", generation + " EN description");
    }

    private static ReferenceMap matchingReferenceMap(
            String ruBody, String enBody, String ruTitle, String enTitle,
            String ruDescription, String enDescription) {
        return ReferenceMap.empty(IDENTITY,
                ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody),
                ContentHash.sha256Hex(ruTitle), ContentHash.sha256Hex(enTitle),
                ContentHash.sha256Hex(ruDescription), ContentHash.sha256Hex(enDescription));
    }

    private static boolean validBackupDirectoryName(String fileName) {
        String prefix = "approved-backup-";
        if (!fileName.startsWith(prefix)) {
            return false;
        }
        try {
            java.util.UUID.fromString(fileName.substring(prefix.length()));
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
