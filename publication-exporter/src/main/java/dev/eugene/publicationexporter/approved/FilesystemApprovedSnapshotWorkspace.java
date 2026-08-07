package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.fs.StagedDirectoryInstall;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

final class FilesystemApprovedSnapshotWorkspace implements ApprovedSnapshotWorkspace {

    private static final int MAX_READ_ATTEMPTS = 5;

    private final StagedDirectoryInstall stagedInstall;
    private final MoveOperation moveOperation;
    private final ReadObserver readObserver;
    private final ThreadLocal<Set<PublicationIdentity>> heldApprovalLocks =
            ThreadLocal.withInitial(HashSet::new);

    FilesystemApprovedSnapshotWorkspace(Path reviewRoot) {
        this(reviewRoot, (source, destination) ->
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE), ignored -> {
                });
    }

    FilesystemApprovedSnapshotWorkspace(Path reviewRoot, MoveOperation moveOperation) {
        this(reviewRoot, moveOperation, ignored -> {
        });
    }

    FilesystemApprovedSnapshotWorkspace(
            Path reviewRoot, MoveOperation moveOperation, ReadObserver readObserver) {
        this.stagedInstall = StagedDirectoryInstall.rootedAt(Objects.requireNonNull(reviewRoot, "reviewRoot"));
        this.moveOperation = Objects.requireNonNull(moveOperation, "moveOperation");
        this.readObserver = Objects.requireNonNull(readObserver, "readObserver");
    }

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription, ReferenceMap referenceMap) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(ruTitle, "ruTitle");
        Objects.requireNonNull(enTitle, "enTitle");
        Objects.requireNonNull(ruDescription, "ruDescription");
        Objects.requireNonNull(enDescription, "enDescription");
        Objects.requireNonNull(referenceMap, "referenceMap");

        withApprovalLock(identity, () -> {
            installUnderLock(identity, ruBody, enBody, ruTitle, enTitle,
                    ruDescription, enDescription, referenceMap);
            return null;
        });
    }

    private void installUnderLock(PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription,
            ReferenceMap referenceMap) {
        recoverIfNeeded(identity, true);
        Path destination = approvedDirectory(identity);
        Path staging = createStagingDirectory();
        try {
            writeSnapshot(staging, ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap);
            requireWithinReviewRoot(destination);
            stagedInstall.createParentDirectories(destination);
            requireWithinReviewRoot(destination);
            replaceApproved(staging, destination);
        } catch (IOException error) {
            StagedDirectoryInstall.deleteRecursively(staging);
            throw new UncheckedIOException(error);
        }
    }

    @Override
    public <T> T withApprovalLock(PublicationIdentity identity, Supplier<T> operation) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(operation, "operation");
        Set<PublicationIdentity> held = heldApprovalLocks.get();
        if (held.contains(identity)) {
            return operation.get();
        }
        Path lockFile = acquireApprovalLock(identity);
        held.add(identity);
        Throwable operationFailure = null;
        try {
            return operation.get();
        } catch (RuntimeException | Error failure) {
            operationFailure = failure;
            throw failure;
        } finally {
            held.remove(identity);
            if (held.isEmpty()) {
                heldApprovalLocks.remove();
            }
            releaseApprovalLock(lockFile, operationFailure);
        }
    }

    private Path acquireApprovalLock(PublicationIdentity identity) {
        Path lockFile = approvalLockFile(identity);
        try {
            stagedInstall.createParentDirectories(lockFile);
            requireWithinReviewRoot(lockFile);
            return Files.createFile(lockFile);
        } catch (FileAlreadyExistsException collision) {
            throw new ApprovedSnapshotApprovalInProgressException(identity);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void releaseApprovalLock(Path lockFile, Throwable operationFailure) {
        try {
            requireWithinReviewRoot(lockFile);
            Files.deleteIfExists(lockFile);
        } catch (IOException | RuntimeException cleanupFailure) {
            if (operationFailure != null) {
                operationFailure.addSuppressed(cleanupFailure);
                return;
            }
            if (cleanupFailure instanceof IOException ioFailure) {
                throw new UncheckedIOException(ioFailure);
            }
            throw (RuntimeException) cleanupFailure;
        }
    }

    private Path approvalLockFile(PublicationIdentity identity) {
        Path parent = approvedDirectory(identity).getParent();
        if (parent == null) {
            throw new ApprovedSnapshotWorkspaceConfinementException(
                    approvedDirectory(identity), approvedDirectory(identity), stagedInstall.canonicalRoot());
        }
        Path lockFile = parent.resolve(".mark-reviewed.lock").normalize();
        requireWithinReviewRoot(lockFile);
        return lockFile;
    }

    private void replaceApproved(Path staging, Path destination) throws IOException {
        Path backup = null;
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            backup = destination.resolveSibling("approved-backup-" + UUID.randomUUID()).normalize();
            moveWithinReviewRoot(destination, backup);
        }
        try {
            moveWithinReviewRoot(staging, destination);
        } catch (IOException installFailure) {
            restoreBackup(backup, destination, installFailure);
            throw installFailure;
        }
        if (backup != null) {
            StagedDirectoryInstall.deleteRecursively(backup);
        }
    }

    private void restoreBackup(Path backup, Path destination, IOException installFailure) {
        if (backup == null) {
            return;
        }
        try {
            moveWithinReviewRoot(backup, destination);
        } catch (IOException | RuntimeException restoreFailure) {
            installFailure.addSuppressed(restoreFailure);
        }
    }

    private void moveWithinReviewRoot(Path source, Path destination) throws IOException {
        requireWithinReviewRoot(source);
        requireWithinReviewRoot(destination);
        moveOperation.move(source, destination);
    }

    private void recoverIfNeeded(PublicationIdentity identity, boolean validateWithoutBackup) {
        Path destination = approvedDirectory(identity);
        Optional<Path> backup = findBackupDirectory(destination);
        if (backup.isEmpty()) {
            if (!validateWithoutBackup) {
                return;
            }
            SnapshotAssessment destinationState = assessSnapshot(destination, identity);
            if (destinationState.invalid()) {
                throw destinationState.failure();
            }
            return;
        }
        SnapshotAssessment destinationState = assessSnapshot(destination, identity);
        SnapshotAssessment backupState = assessSnapshot(backup.get(), identity);
        if (destinationState.valid()) {
            try {
                deleteRecursively(backup.get());
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            throw new ApprovedSnapshotRecoveryException(
                    "Approved snapshot recovery kept the valid canonical snapshot and removed backup "
                            + backup.get() + ". Retry the command.");
        }
        if (backupState.valid()) {
            try {
                if (destinationState.present()) {
                    deleteRecursively(destination);
                }
                moveWithinReviewRoot(backup.get(), destination);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            String reason = destinationState.invalid() ? " after canonical integrity validation failed" : "";
            throw new ApprovedSnapshotRecoveryException(
                    "Approved snapshot recovery restored valid backup " + backup.get() + reason
                            + ". Retry the command.");
        }
        String destinationDetail = destinationState.invalid()
                ? destinationState.failure().getMessage()
                : "canonical snapshot is absent";
        String backupDetail = backupState.invalid()
                ? backupState.failure().getMessage()
                : "backup snapshot is absent";
        throw new ApprovedSnapshotIntegrityException(destination,
                "snapshot is unrecoverable; " + destinationDetail + "; " + backupDetail + ".");
    }

    private Optional<Path> findBackupDirectory(Path destination) {
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        String prefix = destination.getFileName().toString() + "-backup-";
        try (var entries = Files.list(parent)) {
            List<Path> backups = entries
                    .filter(entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                    .filter(entry -> validBackupMarker(entry.getFileName().toString(), prefix))
                    .toList();
            if (backups.size() > 1) {
                throw new ApprovedSnapshotIntegrityException(destination,
                        "multiple recovery backups exist: " + backups + ".");
            }
            return backups.stream().findFirst();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static boolean validBackupMarker(String fileName, String prefix) {
        if (!fileName.startsWith(prefix)) {
            return false;
        }
        String suffix = fileName.substring(prefix.length());
        try {
            return UUID.fromString(suffix).toString().equalsIgnoreCase(suffix);
        } catch (IllegalArgumentException invalidUuid) {
            return false;
        }
    }

    @Override
    public Optional<CandidatePaths> find(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        recoverBeforeAccess(identity);
        Path approvedDirectory = approvedDirectory(identity);
        Optional<CandidateSnapshot> snapshot = stableSnapshotFrom(approvedDirectory, identity);
        if (snapshot.isPresent()) {
            Path ruPath = approvedDirectory.resolve("ru.md");
            Path enPath = approvedDirectory.resolve("en.md");
            return Optional.of(CandidatePaths.of(ruPath, enPath));
        }
        return Optional.empty();
    }

    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        recoverBeforeAccess(identity);
        Path approvedDirectory = approvedDirectory(identity);
        return stableSnapshotFrom(approvedDirectory, identity);
    }

    private void recoverBeforeAccess(PublicationIdentity identity) {
        Path destination = approvedDirectory(identity);
        if (findBackupDirectory(destination).isEmpty()) {
            return;
        }
        withApprovalLock(identity, () -> {
            recoverIfNeeded(identity, false);
            return null;
        });
    }

    private Optional<CandidateSnapshot> stableSnapshotFrom(
            Path approvedDirectory, PublicationIdentity expectedIdentity) {
        for (int attempt = 0; attempt < MAX_READ_ATTEMPTS; attempt++) {
            Object generationBefore = directoryGeneration(approvedDirectory);
            if (generationBefore == null) {
                if (Files.notExists(approvedDirectory, LinkOption.NOFOLLOW_LINKS)
                        && !approvalReplacementInProgress(approvedDirectory)) {
                    return Optional.empty();
                }
                continue;
            }
            CandidateSnapshot snapshot;
            try {
                snapshot = snapshotFrom(approvedDirectory);
            } catch (UncheckedIOException failure) {
                if (generationBefore.equals(directoryGeneration(approvedDirectory))) {
                    throw new ApprovedSnapshotIntegrityException(
                            approvedDirectory, "required snapshot files could not be read.", failure);
                }
                continue;
            }
            Object generationAfter = directoryGeneration(approvedDirectory);
            if (generationBefore.equals(generationAfter)) {
                validateSnapshot(approvedDirectory, expectedIdentity, snapshot);
                return Optional.of(snapshot);
            }
        }
        throw new ApprovedSnapshotWorkspaceStabilizationException(
                approvedDirectory, MAX_READ_ATTEMPTS);
    }

    private static boolean approvalReplacementInProgress(Path approvedDirectory) {
        Path parent = approvedDirectory.getParent();
        return parent != null
                && Files.exists(parent.resolve(".mark-reviewed.lock"), LinkOption.NOFOLLOW_LINKS);
    }

    private Object directoryGeneration(Path approvedDirectory) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    approvedDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                return null;
            }
            return attributes.fileKey();
        } catch (IOException error) {
            return null;
        }
    }

    private CandidateSnapshot snapshotFrom(Path approvedDirectory) {
        try {
            String ruBody = readApprovedText(approvedFile(approvedDirectory, "ru.md"));
            String enBody = readApprovedText(approvedFile(approvedDirectory, "en.md"));
            String ruTitle = readApprovedText(approvedFile(approvedDirectory, "ru.title"));
            String enTitle = readApprovedText(approvedFile(approvedDirectory, "en.title"));
            String ruDescription = readApprovedText(approvedFile(approvedDirectory, "ru.description"));
            String enDescription = readApprovedText(approvedFile(approvedDirectory, "en.description"));
            ReferenceMap referenceMap = readReferenceMap(approvedFile(approvedDirectory, "references.json"));
            return CandidateSnapshot.of(ruBody, enBody, ruTitle, enTitle,
                    ruDescription, enDescription, referenceMap);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private String readApprovedText(Path bodyPath) throws IOException {
        requireWithinReviewRoot(bodyPath);
        String text = Files.readString(bodyPath, StandardCharsets.UTF_8);
        readObserver.afterRead(bodyPath);
        return text;
    }

    private ReferenceMap readReferenceMap(Path referencesPath) throws IOException {
        requireWithinReviewRoot(referencesPath);
        try {
            return ReferenceMapCodec.read(Files.readString(referencesPath, StandardCharsets.UTF_8));
        } catch (UncheckedIOException | IllegalArgumentException | NullPointerException invalidReferenceMap) {
            throw new IOException("references.json is invalid", invalidReferenceMap);
        }
    }

    private static void validateSnapshot(
            Path approvedDirectory, PublicationIdentity expectedIdentity, CandidateSnapshot snapshot) {
        ReferenceMap referenceMap = snapshot.referenceMap();
        List<String> failures = new ArrayList<>();
        if (!referenceMap.identity().equals(expectedIdentity)) {
            failures.add("references.json identity does not match " + expectedIdentity);
        }
        requireHash(failures, "ru.md", snapshot.ruBody(), referenceMap.ruHash());
        requireHash(failures, "en.md", snapshot.enBody(), referenceMap.enHash());
        requireHash(failures, "ru.title", snapshot.ruTitle(), referenceMap.ruTitleHash());
        requireHash(failures, "en.title", snapshot.enTitle(), referenceMap.enTitleHash());
        requireHash(failures, "ru.description", snapshot.ruDescription(), referenceMap.ruDescriptionHash());
        requireHash(failures, "en.description", snapshot.enDescription(), referenceMap.enDescriptionHash());
        if (!failures.isEmpty()) {
            throw new ApprovedSnapshotIntegrityException(
                    approvedDirectory, String.join("; ", failures) + ".");
        }
    }

    private static void requireHash(List<String> failures, String fileName, String content, String expectedHash) {
        if (!ContentHash.sha256Hex(content).equals(expectedHash)) {
            failures.add(fileName + " does not match its recorded hash");
        }
    }

    private SnapshotAssessment assessSnapshot(Path directory, PublicationIdentity expectedIdentity) {
        boolean present = Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
        if (!present) {
            return SnapshotAssessment.absent();
        }
        try {
            Optional<CandidateSnapshot> snapshot = stableSnapshotFrom(directory, expectedIdentity);
            return snapshot.isPresent()
                    ? SnapshotAssessment.validSnapshot()
                    : SnapshotAssessment.absent();
        } catch (ApprovedSnapshotIntegrityException failure) {
            return SnapshotAssessment.invalidSnapshot(failure);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path approvedDirectory(PublicationIdentity identity) {
        Path approved = stagedInstall.canonicalRoot().resolve(identity.publicCollection())
                .resolve(identity.publicId())
                .resolve("approved")
                .normalize();
        requireWithinReviewRoot(approved);
        return approved;
    }

    private Path createStagingDirectory() {
        try {
            return stagedInstall.createStagingDirectory("approved-staging-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void requireWithinReviewRoot(Path candidate) {
        Optional<Path> resolved = stagedInstall.resolveWithinRoot(candidate);
        if (resolved.isEmpty()) {
            throw new ApprovedSnapshotWorkspaceConfinementException(candidate, candidate, stagedInstall.canonicalRoot());
        }
    }

    private Path approvedFile(Path approvedDirectory, String fileName) {
        Path file = approvedDirectory.resolve(fileName).normalize();
        requireWithinReviewRoot(file);
        return file;
    }

    private void writeSnapshot(Path staging, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription, ReferenceMap referenceMap)
            throws IOException {
        Files.writeString(approvedFile(staging, "ru.md"), ruBody, StandardCharsets.UTF_8);
        Files.writeString(approvedFile(staging, "en.md"), enBody, StandardCharsets.UTF_8);
        Files.writeString(approvedFile(staging, "ru.title"), ruTitle, StandardCharsets.UTF_8);
        Files.writeString(approvedFile(staging, "en.title"), enTitle, StandardCharsets.UTF_8);
        Files.writeString(approvedFile(staging, "ru.description"), ruDescription, StandardCharsets.UTF_8);
        Files.writeString(approvedFile(staging, "en.description"), enDescription, StandardCharsets.UTF_8);
        Files.writeString(approvedFile(staging, "references.json"),
                ReferenceMapCodec.write(referenceMap), StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path destination) throws IOException;
    }

    @FunctionalInterface
    interface ReadObserver {
        void afterRead(Path file) throws IOException;
    }

    private record SnapshotAssessment(
            boolean present, boolean valid, ApprovedSnapshotIntegrityException failure) {

        private static SnapshotAssessment absent() {
            return new SnapshotAssessment(false, false, null);
        }

        private static SnapshotAssessment validSnapshot() {
            return new SnapshotAssessment(true, true, null);
        }

        private static SnapshotAssessment invalidSnapshot(ApprovedSnapshotIntegrityException failure) {
            return new SnapshotAssessment(true, false, failure);
        }

        private boolean invalid() {
            return present && !valid;
        }
    }
}
