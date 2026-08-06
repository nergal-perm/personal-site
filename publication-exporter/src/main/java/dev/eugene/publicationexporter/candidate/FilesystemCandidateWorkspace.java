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

        Path destination = candidateDirectory(identity);
        Path staging = createStagingDirectory();
        try {
            writeSnapshot(staging, ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap);
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

    private void writeSnapshot(Path staging, String ruBody, String enBody, String ruTitle, String enTitle,
            String ruDescription, String enDescription, ReferenceMap referenceMap)
            throws IOException {
        Files.writeString(candidateFile(staging, "ru.md"), ruBody, StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "en.md"), enBody, StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "ru.title"), ruTitle, StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "en.title"), enTitle, StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "ru.description"), ruDescription, StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "en.description"), enDescription, StandardCharsets.UTF_8);
        Files.writeString(candidateFile(staging, "references.json"),
                ReferenceMapCodec.write(referenceMap), StandardCharsets.UTF_8);
    }
}
