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
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

final class FilesystemApprovedSnapshotWorkspace implements ApprovedSnapshotWorkspace {

    private final StagedDirectoryInstall stagedInstall;

    FilesystemApprovedSnapshotWorkspace(Path reviewRoot) {
        this.stagedInstall = StagedDirectoryInstall.rootedAt(Objects.requireNonNull(reviewRoot, "reviewRoot"));
    }

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(referenceMap, "referenceMap");

        Path destination = approvedDirectory(identity);
        if (Files.exists(destination)) {
            throw new ApprovedSnapshotAlreadyExistsException(identity);
        }
        Path staging = createStagingDirectory();
        try {
            writeTriple(staging, ruBody, enBody, referenceMap);
            requireWithinReviewRoot(destination);
            stagedInstall.createParentDirectories(destination);
            requireWithinReviewRoot(destination);
            stagedInstall.move(staging, destination);
        } catch (IOException error) {
            StagedDirectoryInstall.deleteRecursively(staging);
            throw new UncheckedIOException(error);
        }
    }

    @Override
    public Optional<CandidatePaths> find(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
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
        Path approvedDirectory = approvedDirectory(identity);
        if (!containsApprovedTriple(approvedDirectory)) {
            return Optional.empty();
        }
        return snapshotFrom(approvedDirectory, identity);
    }

    private static boolean containsApprovedTriple(Path approvedDirectory) {
        return Files.exists(approvedDirectory.resolve("ru.md"))
                && Files.exists(approvedDirectory.resolve("en.md"))
                && Files.exists(approvedDirectory.resolve("references.json"));
    }

    private Optional<CandidateSnapshot> snapshotFrom(
            Path approvedDirectory, PublicationIdentity expectedIdentity) {
        try {
            String ruBody = readApprovedBody(approvedDirectory.resolve("ru.md"));
            String enBody = readApprovedBody(approvedDirectory.resolve("en.md"));
            ReferenceMap referenceMap = readReferenceMap(approvedDirectory.resolve("references.json"));
            return snapshotMatching(expectedIdentity, ruBody, enBody, referenceMap);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private String readApprovedBody(Path bodyPath) throws IOException {
        requireWithinReviewRoot(bodyPath);
        return Files.readString(bodyPath, StandardCharsets.UTF_8);
    }

    private ReferenceMap readReferenceMap(Path referencesPath) throws IOException {
        requireWithinReviewRoot(referencesPath);
        return ReferenceMapCodec.read(Files.readString(referencesPath, StandardCharsets.UTF_8));
    }

    private static Optional<CandidateSnapshot> snapshotMatching(
            PublicationIdentity expectedIdentity, String ruBody, String enBody, ReferenceMap referenceMap) {
        if (!referenceMap.identity().equals(expectedIdentity)) {
            return Optional.empty();
        }
        return Optional.of(CandidateSnapshot.of(ruBody, enBody, referenceMap));
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

    private void writeTriple(Path staging, String ruBody, String enBody, ReferenceMap referenceMap)
            throws IOException {
        Files.writeString(staging.resolve("ru.md"), ruBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("en.md"), enBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("references.json"),
                ReferenceMapCodec.write(referenceMap), StandardCharsets.UTF_8);
    }
}
