package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
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

final class FilesystemCandidateWorkspace implements CandidateWorkspace {

    private final StagedDirectoryInstall stagedInstall;

    FilesystemCandidateWorkspace(Path reviewRoot) {
        this.stagedInstall = StagedDirectoryInstall.rootedAt(Objects.requireNonNull(reviewRoot, "reviewRoot"));
    }

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(referenceMap, "referenceMap");

        Path destination = candidateDirectory(identity);
        Path staging = createStagingDirectory();
        try {
            writeTriple(staging, ruBody, enBody, referenceMap);
            requireWithinReviewRoot(destination);
            stagedInstall.moveIntoPlace(staging, destination);
        } catch (IOException error) {
            StagedDirectoryInstall.deleteRecursively(staging);
            throw new UncheckedIOException(error);
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
        if (!containsCandidateTriple(candidateDirectory)) {
            return Optional.empty();
        }
        return snapshotFrom(candidateDirectory, identity);
    }

    private static boolean containsCandidateTriple(Path candidateDirectory) {
        return Files.exists(candidateDirectory.resolve("ru.md"))
                && Files.exists(candidateDirectory.resolve("en.md"))
                && Files.exists(candidateDirectory.resolve("references.json"));
    }

    private Optional<CandidateSnapshot> snapshotFrom(
            Path candidateDirectory, PublicationIdentity expectedIdentity) {
        try {
            String ruBody = readCandidateBody(candidateDirectory.resolve("ru.md"));
            String enBody = readCandidateBody(candidateDirectory.resolve("en.md"));
            ReferenceMap referenceMap = readReferenceMap(candidateDirectory.resolve("references.json"));
            return snapshotMatching(expectedIdentity, ruBody, enBody, referenceMap);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private String readCandidateBody(Path bodyPath) throws IOException {
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
        Optional<Path> resolved = stagedInstall.resolveWithinRoot(candidate);
        if (resolved.isEmpty()) {
            throw new CandidateWorkspaceConfinementException(candidate, candidate, stagedInstall.canonicalRoot());
        }
        if (!resolved.get().equals(candidate) && !candidate.startsWith(stagedInstall.canonicalRoot())) {
            throw new CandidateWorkspaceConfinementException(candidate, resolved.get(), stagedInstall.canonicalRoot());
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
