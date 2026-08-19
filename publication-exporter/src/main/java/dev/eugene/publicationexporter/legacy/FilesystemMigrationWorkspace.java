package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Files;

final class FilesystemMigrationWorkspace implements MigrationWorkspace {
    private static final List<String> SNAPSHOT_FILES = List.of(
            "ru.md", "en.md", "ru.fields.json", "en.fields.json", "structured.json", "references.json");
    private final CandidateWorkspace candidates;
    private final ApprovedSnapshotWorkspace approved;
    private final Path root;
    private final RestoreOperation restoreOperation;

    FilesystemMigrationWorkspace(Path reviewRoot) {
        this(reviewRoot, null);
    }

    FilesystemMigrationWorkspace(Path reviewRoot, RestoreOperation operation) {
        this.root = FilesystemMigrationPath.safeRoot(Objects.requireNonNull(reviewRoot, "reviewRoot"));
        candidates = CandidateWorkspace.create(this.root);
        approved = ApprovedSnapshotWorkspace.create(this.root);
        restoreOperation = operation == null ? new DefaultRestoreOperation() : operation;
    }

    @Override
    public void preflight(MigrationGeneration generation) {
        Objects.requireNonNull(generation, "generation");
        generation.identities().forEach(identity -> {
            preflightSnapshot(identity, "candidate");
            preflightSnapshot(identity, "approved");
        });
    }

    @Override
    public MigrationPreimage capture(MigrationGeneration generation) {
        Objects.requireNonNull(generation, "generation");
        preflight(generation);
        Map<PublicationIdentity, CandidateSnapshot> candidateSnapshots = new LinkedHashMap<>();
        Map<PublicationIdentity, CandidateSnapshot> approvedSnapshots = new LinkedHashMap<>();
        Map<PublicationIdentity, List<dev.eugene.publicationexporter.candidate.CandidateAsset>> assets = new LinkedHashMap<>();
        for (PublicationIdentity identity : generation.identities()) {
            candidates.read(identity).ifPresent(snapshot -> candidateSnapshots.put(identity, snapshot));
            readAssets(identity).ifPresent(values -> assets.put(identity, values));
            approved.read(identity).ifPresent(snapshot -> approvedSnapshots.put(identity, snapshot));
        }
        return new MigrationPreimage(generation, candidateSnapshots, approvedSnapshots, assets);
    }

    @Override
    public void apply(MigrationGeneration generation, MigrationPreimage preimage, int step) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(preimage, "preimage");
        if (!preimage.belongsTo(generation)) {
            throw new MigrationRecoveryException("Migration step preimage does not belong to its generation.");
        }
        if (!generation.isRunning() || step < 0 || step >= generation.identities().size()) {
            throw new MigrationRecoveryException("Migration workspace step is outside the running generation.");
        }
        PublicationIdentity identity = generation.identities().get(step);
        preflightSnapshot(identity, "candidate");
        preflightSnapshot(identity, "approved");
        IdentityState expected = stateOf(preimage, identity);
        IdentityState current = currentState(identity);
        if (!current.equals(expected)) {
            throw new MigrationRecoveryException("Migration workspace changed after its role manifest was captured.");
        }
        expected.candidate().ifPresent(snapshot -> candidates.install(identity, snapshot, expected.assets()));
        expected.approved().ifPresent(snapshot -> approved.install(identity, snapshot));
        if (expected.candidate().isEmpty() && expected.approved().isEmpty()) {
            throw new MigrationRecoveryException("No candidate or approved snapshot exists for " + identity);
        }
    }

    @Override
    public void verify(MigrationGeneration generation, MigrationPreimage preimage) {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(preimage, "preimage");
        if (!preimage.belongsTo(generation)) {
            throw new MigrationRecoveryException("Migration preimage does not belong to the journal generation.");
        }
        MigrationPreimage actual;
        try {
            actual = capture(generation);
        } catch (MigrationRecoveryException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new MigrationRecoveryException(
                    "Migration workspace contains an invalid snapshot: " + failure.getMessage());
        }
        for (int step = 0; step < generation.identities().size(); step++) {
            PublicationIdentity identity = generation.identities().get(step);
            IdentityState before = stateOf(preimage, identity);
            IdentityState applied = appliedState(before);
            IdentityState current = stateOf(actual, identity);
            if (!allowedAtCursor(generation, step, before, applied, current)) {
                throw new MigrationRecoveryException("Migration workspace differs from its journaled preimage or applied targets.");
            }
        }
    }

    private static boolean allowedAtCursor(MigrationGeneration generation, int step,
            IdentityState before, IdentityState applied, IdentityState current) {
        if (step < generation.completedSteps()) {
            return current.equals(applied);
        }
        if (generation.isRunning() && step == generation.completedSteps()) {
            return current.equals(before) || current.equals(applied);
        }
        return current.equals(before);
    }

    private static IdentityState appliedState(IdentityState before) {
        return before;
    }

    private static IdentityState stateOf(MigrationPreimage image, PublicationIdentity identity) {
        return new IdentityState(java.util.Optional.ofNullable(image.candidateSnapshots().get(identity)),
                java.util.Optional.ofNullable(image.approvedSnapshots().get(identity)),
                image.candidateAssets().getOrDefault(identity, List.of()));
    }

    private IdentityState currentState(PublicationIdentity identity) {
        return new IdentityState(candidates.read(identity), approved.read(identity),
                readAssets(identity).orElse(List.of()));
    }

    @Override
    public void restore(MigrationPreimage preimage) {
        Objects.requireNonNull(preimage, "preimage");
        preflight(preimage.generation());
        validateRestoreTargets(preimage);
        MigrationPreimage current = captureCurrentWorkspace(preimage);
        restoreWithCompensation(preimage, current);
    }

    private void validateRestoreTargets(MigrationPreimage preimage) {
        preimage.generation().identities().forEach(identity -> {
            FilesystemMigrationPath.requireSafe(root, counterpart(identity, "candidate"));
            FilesystemMigrationPath.requireSafe(root, counterpart(identity, "approved"));
        });
    }

    private MigrationPreimage captureCurrentWorkspace(MigrationPreimage preimage) {
        MigrationGeneration generation = new MigrationGeneration(preimage.generation().inventorySha256(),
                preimage.generation().identities(), 0, MigrationState.RUNNING);
        return capture(generation);
    }

    private void restoreWithCompensation(MigrationPreimage target, MigrationPreimage current) {
        try {
            restoreRecorded(target);
        } catch (RuntimeException failure) {
            try { restoreRecorded(current); }
            catch (RuntimeException compensationFailure) { failure.addSuppressed(compensationFailure); }
            throw new MigrationRecoveryException("Filesystem rollback failed and compensation was attempted: " + failure.getMessage());
        }
    }

    private void restoreRecorded(MigrationPreimage preimage) {
        restoreRecordedSnapshots(preimage);
        deleteMissingCounterparts(preimage);
    }

    private void restoreRecordedSnapshots(MigrationPreimage preimage) {
        preimage.candidateSnapshots().forEach((identity, snapshot) -> restoreOperation.installCandidate(identity, snapshot,
                preimage.candidateAssets().getOrDefault(identity, List.of())));
        preimage.approvedSnapshots().forEach(restoreOperation::installApproved);
    }

    private void deleteMissingCounterparts(MigrationPreimage preimage) {
        preimage.generation().identities().stream().filter(identity -> !preimage.candidateSnapshots().containsKey(identity))
                .forEach(identity -> restoreOperation.delete(counterpart(identity, "candidate")));
        preimage.generation().identities().stream().filter(identity -> !preimage.approvedSnapshots().containsKey(identity))
                .forEach(identity -> restoreOperation.delete(counterpart(identity, "approved")));
    }

    private Path counterpart(PublicationIdentity identity, String kind) {
        return root.resolve(identity.publicCollection()).resolve(identity.publicId()).resolve(kind);
    }

    private void preflightSnapshot(PublicationIdentity identity, String role) {
        Path directory = counterpart(identity, role);
        FilesystemMigrationPath.requireDirectoryOrAbsent(root, directory);
        SNAPSHOT_FILES.forEach(file ->
                FilesystemMigrationPath.requireRegularFileOrAbsent(root, directory.resolve(file)));
        if ("candidate".equals(role)) {
            preflightCandidateAssets(directory.resolve("assets"));
        }
        rejectNestedSymbolicLinks(directory);
    }

    private void preflightCandidateAssets(Path assets) {
        FilesystemMigrationPath.requireDirectoryOrAbsent(root, assets);
        rejectNestedSymbolicLinks(assets);
    }

    private void rejectNestedSymbolicLinks(Path directory) {
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.forEach(path -> FilesystemMigrationPath.requireSafe(root, path));
        } catch (java.io.IOException error) {
            throw new MigrationRecoveryException("Cannot preflight migration snapshot: " + directory);
        }
    }

    interface RestoreOperation {
        void installCandidate(PublicationIdentity identity, CandidateSnapshot snapshot, List<dev.eugene.publicationexporter.candidate.CandidateAsset> assets);
        void installApproved(PublicationIdentity identity, CandidateSnapshot snapshot);
        void delete(Path directory);
    }

    private record IdentityState(java.util.Optional<CandidateSnapshot> candidate,
            java.util.Optional<CandidateSnapshot> approved,
            List<dev.eugene.publicationexporter.candidate.CandidateAsset> assets) {
        private IdentityState {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(approved, "approved");
            assets = List.copyOf(assets);
        }
    }

    private final class DefaultRestoreOperation implements RestoreOperation {
        public void installCandidate(PublicationIdentity identity, CandidateSnapshot snapshot, List<dev.eugene.publicationexporter.candidate.CandidateAsset> assets) { candidates.install(identity, snapshot, assets); }
        public void installApproved(PublicationIdentity identity, CandidateSnapshot snapshot) { approved.install(identity, snapshot); }
        public void delete(Path directory) { deleteDirectory(directory); }
    }

    private java.util.Optional<List<dev.eugene.publicationexporter.candidate.CandidateAsset>> readAssets(PublicationIdentity identity) {
        Path directory = assetsDirectory(identity);
        validateAssetsDirectory(directory);
        if (!Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return java.util.Optional.empty();
        return readAssetEntries(directory);
    }

    private Path assetsDirectory(PublicationIdentity identity) {
        return root.resolve(identity.publicCollection()).resolve(identity.publicId()).resolve("candidate/assets");
    }

    private void validateAssetsDirectory(Path directory) {
        FilesystemMigrationPath.requireSafe(root, directory);
        if (Files.isSymbolicLink(directory)) {
            throw new MigrationRecoveryException("Candidate assets directory is symbolic: " + directory);
        }
    }

    private java.util.Optional<List<dev.eugene.publicationexporter.candidate.CandidateAsset>> readAssetEntries(Path directory) {
        try (var paths = Files.list(directory)) {
            return java.util.Optional.of(paths.sorted().map(this::readCandidateAsset).toList());
        } catch (java.io.IOException error) { throw new MigrationRecoveryException("Cannot list candidate assets."); }
    }

    private dev.eugene.publicationexporter.candidate.CandidateAsset readCandidateAsset(Path path) {
        FilesystemMigrationPath.requireSafe(root, path);
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationRecoveryException("Candidate asset is not a regular file: " + path);
        }
        try {
            return dev.eugene.publicationexporter.candidate.CandidateAsset.of(path.getFileName().toString(), Files.readAllBytes(path));
        } catch (java.io.IOException error) {
            throw new MigrationRecoveryException("Cannot read candidate asset: " + path);
        }
    }

    private void deleteDirectory(Path directory) {
        validateDeletableDirectory(directory);
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        deleteDirectoryContents(directory);
    }

    private void validateDeletableDirectory(Path directory) {
        FilesystemMigrationPath.requireSafe(root, directory);
    }

    private void deleteDirectoryContents(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(this::deleteDirectoryEntry);
        } catch (java.io.IOException error) { throw new MigrationRecoveryException("Cannot remove counterpart: " + directory); }
    }

    private void deleteDirectoryEntry(Path path) {
        FilesystemMigrationPath.requireSafe(root, path);
        try {
            Files.deleteIfExists(path);
        } catch (java.io.IOException error) {
            throw new MigrationRecoveryException("Cannot remove counterpart: " + path);
        }
    }
}
