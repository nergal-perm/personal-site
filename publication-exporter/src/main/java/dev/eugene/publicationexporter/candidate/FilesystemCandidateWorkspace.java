package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;

public final class FilesystemCandidateWorkspace implements CandidateWorkspace {

    private final Path reviewRoot;

    public FilesystemCandidateWorkspace(Path reviewRoot) {
        this.reviewRoot = Objects.requireNonNull(reviewRoot, "reviewRoot");
    }

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        Path staging = createStagingDirectory();
        try {
            writeTriple(staging, ruBody, enBody, referenceMap);
            Path destination = candidateDirectory(identity);
            Files.createDirectories(destination.getParent());
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            deleteRecursively(staging);
            throw new UncheckedIOException(error);
        }
    }

    private Path candidateDirectory(PublicationIdentity identity) {
        return reviewRoot.resolve(identity.publicCollection()).resolve(identity.publicId()).resolve("candidate");
    }

    private Path createStagingDirectory() {
        try {
            Files.createDirectories(reviewRoot);
            return Files.createTempDirectory(reviewRoot, "candidate-staging-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void writeTriple(Path staging, String ruBody, String enBody, ReferenceMap referenceMap)
            throws IOException {
        Files.writeString(staging.resolve("ru.md"), ruBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("en.md"), enBody, StandardCharsets.UTF_8);
        Files.writeString(staging.resolve("references.json"),
                ReferenceMapCodec.write(referenceMap), StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(FilesystemCandidateWorkspace::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort staging cleanup after a failed install
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort; see deleteRecursively
        }
    }
}
