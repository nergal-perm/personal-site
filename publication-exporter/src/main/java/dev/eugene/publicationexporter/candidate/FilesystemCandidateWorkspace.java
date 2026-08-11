package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class FilesystemCandidateWorkspace implements CandidateWorkspace {

    private final StagedDirectoryInstall stagedInstall;
    private final MoveOperation moveOperation;

    FilesystemCandidateWorkspace(Path reviewRoot) {
        this(reviewRoot, (source, destination) ->
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE));
    }

    FilesystemCandidateWorkspace(Path reviewRoot, MoveOperation moveOperation) {
        this.stagedInstall = StagedDirectoryInstall.rootedAt(Objects.requireNonNull(reviewRoot, "reviewRoot"));
        this.moveOperation = Objects.requireNonNull(moveOperation, "moveOperation");
    }

    @Override
    public void install(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(assets, "assets");

        Path destination = candidateDirectory(identity);
        requireNoKindCollision(identity, destination);
        Path staging = createStagingDirectory();
        try {
            writeSnapshot(staging, content);
            writeAssets(staging, assets);
            requireWithinReviewRoot(destination);
            stagedInstall.createParentDirectories(destination);
            requireWithinReviewRoot(destination);
            replaceCandidate(staging, destination);
        } catch (IOException error) {
            StagedDirectoryInstall.deleteRecursively(staging);
            throw new UncheckedIOException(error);
        } catch (CandidateWorkspaceConfinementException error) {
            StagedDirectoryInstall.deleteRecursively(staging);
            throw error;
        }
    }

    private void replaceCandidate(Path staging, Path destination) throws IOException {
        Path backup = null;
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            backup = destination.resolveSibling("candidate-backup-" + UUID.randomUUID()).normalize();
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

    /**
     * The real destination path is keyed by (collection, publicId) only, so a different kind
     * sharing that pair would otherwise silently overwrite this one's candidate on replace.
     * references.json already carries the full identity of whatever currently occupies the
     * directory, so the existing snapshot's kind is checked against the incoming one before
     * any write happens.
     */
    private void requireNoKindCollision(PublicationIdentity identity, Path destination) {
        Path referencesPath = candidateFile(destination, "references.json");
        if (!Files.exists(referencesPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        PublicationIdentity existingIdentity;
        try {
            existingIdentity = readReferenceMap(referencesPath).identity();
        } catch (IOException | RuntimeException unreadable) {
            return;
        }
        if (!existingIdentity.equals(identity)) {
            throw new CandidateWorkspaceKindCollisionException(identity, existingIdentity);
        }
    }

    @Override
    public Optional<CandidatePaths> find(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Path candidateDirectory = candidateDirectory(identity);
        Path ruPath = candidateDirectory.resolve("ru.md");
        Path enPath = candidateDirectory.resolve("en.md");
        if (Files.exists(ruPath) && Files.exists(enPath)) {
            return Optional.of(CandidatePaths.of(ruPath, enPath));
        }
        return Optional.empty();
    }

    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Path candidateDirectory = candidateDirectory(identity);
        if (!containsCandidateSnapshot(candidateDirectory)) {
            return Optional.empty();
        }
        return snapshotFrom(candidateDirectory, identity);
    }

    private boolean containsCandidateSnapshot(Path candidateDirectory) {
        Path ruBodyPath = candidateFile(candidateDirectory, "ru.md");
        Path enBodyPath = candidateFile(candidateDirectory, "en.md");
        Path ruTitlePath = candidateFile(candidateDirectory, "ru.title");
        Path enTitlePath = candidateFile(candidateDirectory, "en.title");
        Path ruDescriptionPath = candidateFile(candidateDirectory, "ru.description");
        Path enDescriptionPath = candidateFile(candidateDirectory, "en.description");
        Path referencesPath = candidateFile(candidateDirectory, "references.json");
        return Files.exists(ruBodyPath) && Files.exists(enBodyPath)
                && Files.exists(ruTitlePath) && Files.exists(enTitlePath)
                && Files.exists(ruDescriptionPath) && Files.exists(enDescriptionPath)
                && Files.exists(referencesPath);
    }

    private Optional<CandidateSnapshot> snapshotFrom(
            Path candidateDirectory, PublicationIdentity expectedIdentity) {
        try {
            String ruBody = readCandidateText(candidateFile(candidateDirectory, "ru.md"));
            String enBody = readCandidateText(candidateFile(candidateDirectory, "en.md"));
            String ruTitle = readCandidateText(candidateFile(candidateDirectory, "ru.title"));
            String enTitle = readCandidateText(candidateFile(candidateDirectory, "en.title"));
            String ruDescription = readCandidateText(candidateFile(candidateDirectory, "ru.description"));
            String enDescription = readCandidateText(candidateFile(candidateDirectory, "en.description"));
            ReferenceMap referenceMap = readReferenceMap(candidateFile(candidateDirectory, "references.json"));
            return snapshotMatching(expectedIdentity, ruBody, enBody, ruTitle, enTitle,
                    ruDescription, enDescription, referenceMap);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private String readCandidateText(Path bodyPath) throws IOException {
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

    private Path candidateDirectory(PublicationIdentity identity) {
        Path candidate = stagedInstall.canonicalRoot().resolve(identity.publicCollection())
                .resolve(identity.publicId())
                .resolve("candidate")
                .normalize();
        requireWithinReviewRoot(candidate);
        return candidate;
    }

    private Path createStagingDirectory() {
        try {
            return stagedInstall.createStagingDirectory("candidate-staging-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void requireWithinReviewRoot(Path candidate) {
        if (stagedInstall.resolveWithinRoot(candidate).isEmpty()) {
            throw new CandidateWorkspaceConfinementException(candidate, candidate, stagedInstall.canonicalRoot());
        }
    }

    private Path candidateFile(Path candidateDirectory, String fileName) {
        Path file = candidateDirectory.resolve(fileName).normalize();
        requireWithinReviewRoot(file);
        return file;
    }

    private void writeSnapshot(Path staging, CandidateSnapshot content) throws IOException {
        Files.writeString(candidateFile(staging, "ru.md"), content.ruBody(), StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "en.md"), content.enBody(), StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "ru.title"), content.ruTitle(), StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "en.title"), content.enTitle(), StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "ru.description"), content.ruDescription(), StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "en.description"), content.enDescription(), StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "references.json"),
                ReferenceMapCodec.write(content.referenceMap()), StandardCharsets.UTF_8);
    }

    private void writeAssets(Path staging, List<CandidateAsset> assets) throws IOException {
        Path assetsDirectory = candidateFile(staging, "assets");
        for (CandidateAsset asset : assets) {
            Path assetFile = candidateFile(assetsDirectory, asset.publicName());
            requireDirectChild(assetFile, assetsDirectory);
            Files.createDirectories(assetFile.getParent());
            Files.write(assetFile, asset.content());
        }
    }

    private static void requireDirectChild(Path assetFile, Path assetsDirectory) {
        if (!assetFile.getParent().equals(assetsDirectory)) {
            throw new CandidateWorkspaceConfinementException(assetFile, assetFile, assetsDirectory);
        }
    }

    @FunctionalInterface
    interface MoveOperation {

        void move(Path source, Path destination) throws IOException;
    }
}
