package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.fs.StagedDirectoryInstall;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class FilesystemApprovedSnapshotWorkspace implements ApprovedSnapshotWorkspace {

    private final StagedDirectoryInstall stagedInstall;
    private final MoveOperation moveOperation;

    FilesystemApprovedSnapshotWorkspace(Path reviewRoot) {
        this(reviewRoot, (source, destination) ->
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE));
    }

    FilesystemApprovedSnapshotWorkspace(Path reviewRoot, MoveOperation moveOperation) {
        this.stagedInstall = StagedDirectoryInstall.rootedAt(Objects.requireNonNull(reviewRoot, "reviewRoot"));
        this.moveOperation = Objects.requireNonNull(moveOperation, "moveOperation");
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

        recoverIfNeeded(identity);
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

    // Assumes at most one backup per identity: true within one JVM under MarkReviewedHandler's lock,
    // but not across racing CLI processes; accepted residual per design.md's Risks and
    // dec-20260807-s08-translation-worker-trust-boundary-8bab0bc6 (single-operator deployment model).
    private void recoverIfNeeded(PublicationIdentity identity) {
        Path destination = approvedDirectory(identity);
        Optional<Path> backup = findBackupDirectory(destination);
        if (backup.isEmpty()) {
            return;
        }
        boolean destinationComplete = containsApprovedSnapshot(destination);
        boolean backupComplete = containsApprovedSnapshot(backup.get());
        if (destinationComplete) {
            StagedDirectoryInstall.deleteRecursively(backup.get());
            return;
        }
        if (backupComplete) {
            try {
                moveWithinReviewRoot(backup.get(), destination);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            return;
        }
        throw new IllegalStateException(
                "Approved snapshot for " + identity + " is unrecoverable: neither " + destination
                        + " nor its backup " + backup.get() + " is a complete snapshot.");
    }

    private Optional<Path> findBackupDirectory(Path destination) {
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return Optional.empty();
        }
        String prefix = destination.getFileName().toString() + "-backup-";
        try (var entries = Files.list(parent)) {
            return entries.filter(Files::isDirectory)
                    .filter(entry -> entry.getFileName().toString().startsWith(prefix))
                    .findFirst();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Override
    public Optional<CandidatePaths> find(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        recoverIfNeeded(identity);
        Path approvedDirectory = approvedDirectory(identity);
        Path ruPath = approvedDirectory.resolve("ru.md");
        Path enPath = approvedDirectory.resolve("en.md");
        if (Files.exists(ruPath) && Files.exists(enPath)) {
            return Optional.of(CandidatePaths.of(ruPath, enPath));
        }
        return Optional.empty();
    }

    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        recoverIfNeeded(identity);
        Path approvedDirectory = approvedDirectory(identity);
        if (!containsApprovedSnapshot(approvedDirectory)) {
            return Optional.empty();
        }
        return snapshotFrom(approvedDirectory, identity);
    }

    private boolean containsApprovedSnapshot(Path approvedDirectory) {
        return Files.exists(approvedFile(approvedDirectory, "ru.md"))
                && Files.exists(approvedFile(approvedDirectory, "en.md"))
                && Files.exists(approvedFile(approvedDirectory, "ru.title"))
                && Files.exists(approvedFile(approvedDirectory, "en.title"))
                && Files.exists(approvedFile(approvedDirectory, "ru.description"))
                && Files.exists(approvedFile(approvedDirectory, "en.description"))
                && Files.exists(approvedFile(approvedDirectory, "references.json"));
    }

    private Optional<CandidateSnapshot> snapshotFrom(
            Path approvedDirectory, PublicationIdentity expectedIdentity) {
        try {
            String ruBody = readApprovedText(approvedFile(approvedDirectory, "ru.md"));
            String enBody = readApprovedText(approvedFile(approvedDirectory, "en.md"));
            String ruTitle = readApprovedText(approvedFile(approvedDirectory, "ru.title"));
            String enTitle = readApprovedText(approvedFile(approvedDirectory, "en.title"));
            String ruDescription = readApprovedText(approvedFile(approvedDirectory, "ru.description"));
            String enDescription = readApprovedText(approvedFile(approvedDirectory, "en.description"));
            ReferenceMap referenceMap = readReferenceMap(approvedFile(approvedDirectory, "references.json"));
            return snapshotMatching(expectedIdentity, ruBody, enBody, ruTitle, enTitle,
                    ruDescription, enDescription, referenceMap);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private String readApprovedText(Path bodyPath) throws IOException {
        requireWithinReviewRoot(bodyPath);
        return Files.readString(bodyPath, StandardCharsets.UTF_8);
    }

    private ReferenceMap readReferenceMap(Path referencesPath) throws IOException {
        requireWithinReviewRoot(referencesPath);
        return ReferenceMapCodec.read(Files.readString(referencesPath, StandardCharsets.UTF_8));
    }

    private static Optional<CandidateSnapshot> snapshotMatching(
            PublicationIdentity expectedIdentity, String ruBody, String enBody, String ruTitle, String enTitle,
            String ruDescription, String enDescription, ReferenceMap referenceMap) {
        if (!referenceMap.identity().equals(expectedIdentity)) {
            return Optional.empty();
        }
        return Optional.of(CandidateSnapshot.of(ruBody, enBody, ruTitle, enTitle,
                ruDescription, enDescription, referenceMap));
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
}
