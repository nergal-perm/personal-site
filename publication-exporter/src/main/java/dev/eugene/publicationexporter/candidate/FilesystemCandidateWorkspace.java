package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

final class FilesystemCandidateWorkspace implements CandidateWorkspace {

    private final Path canonicalReviewRoot;

    FilesystemCandidateWorkspace(Path reviewRoot) {
        this.canonicalReviewRoot = canonicalize(Objects.requireNonNull(reviewRoot, "reviewRoot"));
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
            publishStagingCandidate(staging, destination);
        } catch (IOException error) {
            deleteRecursively(staging);
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

    private void publishStagingCandidate(Path staging, Path destination) throws IOException {
        requireWithinReviewRoot(destination);
        Files.createDirectories(destination.getParent());
        requireWithinReviewRoot(destination);
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path candidateDirectory(PublicationIdentity identity) {
        Path candidate = canonicalReviewRoot.resolve(identity.publicCollection())
                .resolve(identity.publicId())
                .resolve("candidate")
                .normalize();
        requireWithinReviewRoot(candidate);
        return candidate;
    }

    private Path createStagingDirectory() {
        try {
            Files.createDirectories(canonicalReviewRoot);
            return Files.createTempDirectory(canonicalReviewRoot, "candidate-staging-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void requireWithinReviewRoot(Path candidate) {
        if (!candidate.startsWith(canonicalReviewRoot)) {
            throw new CandidateWorkspaceConfinementException(
                    candidate, candidate, canonicalReviewRoot);
        }
        if (Files.notExists(canonicalReviewRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path resolvedCandidate = resolveThroughNearestExistingAncestor(candidate);
        Path resolvedReviewRoot = realPathOf(canonicalReviewRoot).orElse(canonicalReviewRoot);
        if (!resolvedCandidate.startsWith(resolvedReviewRoot)) {
            throw new CandidateWorkspaceConfinementException(
                    candidate, resolvedCandidate, resolvedReviewRoot);
        }
    }

    private static Path resolveThroughNearestExistingAncestor(Path candidate) {
        Path existingAncestor = candidate;
        while (existingAncestor != null
                && !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) {
            return candidate.toAbsolutePath().normalize();
        }
        try {
            Path realAncestor = existingAncestor.toRealPath();
            return realAncestor.resolve(existingAncestor.relativize(candidate)).normalize();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static Path canonicalize(Path reviewRoot) {
        return realPathOf(reviewRoot).orElseGet(() -> reviewRoot.toAbsolutePath().normalize());
    }

    private static Optional<Path> realPathOf(Path path) {
        try {
            return Optional.of(path.toRealPath());
        } catch (IOException | SecurityException unresolvable) {
            return Optional.empty();
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
