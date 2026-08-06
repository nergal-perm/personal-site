package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
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

final class FilesystemApprovedSnapshotWorkspace implements ApprovedSnapshotWorkspace {

    private final Path canonicalReviewRoot;

    FilesystemApprovedSnapshotWorkspace(Path reviewRoot) {
        this.canonicalReviewRoot = canonicalize(Objects.requireNonNull(reviewRoot, "reviewRoot"));
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
            publishStagingApproved(staging, destination);
        } catch (IOException error) {
            deleteRecursively(staging);
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

    private void publishStagingApproved(Path staging, Path destination) throws IOException {
        requireWithinReviewRoot(destination);
        Files.createDirectories(destination.getParent());
        requireWithinReviewRoot(destination);
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path approvedDirectory(PublicationIdentity identity) {
        Path approved = canonicalReviewRoot.resolve(identity.publicCollection())
                .resolve(identity.publicId())
                .resolve("approved")
                .normalize();
        requireWithinReviewRoot(approved);
        return approved;
    }

    private Path createStagingDirectory() {
        try {
            Files.createDirectories(canonicalReviewRoot);
            return Files.createTempDirectory(canonicalReviewRoot, "approved-staging-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private void requireWithinReviewRoot(Path candidate) {
        if (!candidate.startsWith(canonicalReviewRoot)) {
            throw new ApprovedSnapshotWorkspaceConfinementException(
                    candidate, candidate, canonicalReviewRoot);
        }
        if (Files.notExists(canonicalReviewRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path resolvedCandidate = resolveThroughNearestExistingAncestor(candidate);
        Path resolvedReviewRoot = realPathOf(canonicalReviewRoot).orElse(canonicalReviewRoot);
        if (!resolvedCandidate.startsWith(resolvedReviewRoot)) {
            throw new ApprovedSnapshotWorkspaceConfinementException(
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
            paths.sorted(Comparator.reverseOrder()).forEach(FilesystemApprovedSnapshotWorkspace::deleteQuietly);
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
