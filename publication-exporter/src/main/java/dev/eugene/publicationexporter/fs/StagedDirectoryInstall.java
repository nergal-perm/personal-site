package dev.eugene.publicationexporter.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.Objects;

public final class StagedDirectoryInstall {

    private final Path canonicalRoot;

    private StagedDirectoryInstall(Path root) {
        this.canonicalRoot = canonicalize(Objects.requireNonNull(root, "root"));
    }

    public static StagedDirectoryInstall rootedAt(Path root) {
        return new StagedDirectoryInstall(root);
    }

    public Path canonicalRoot() {
        return canonicalRoot;
    }

    public Path createStagingDirectory(String prefix) throws IOException {
        Files.createDirectories(canonicalRoot);
        return Files.createTempDirectory(canonicalRoot, prefix);
    }

    public void createParentDirectories(Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
    }

    public void move(Path staging, Path destination) throws IOException {
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The resolved real path of {@code candidate} if it lies within this root, empty if it escapes. */
    public Optional<Path> resolveWithinRoot(Path candidate) {
        if (!candidate.startsWith(canonicalRoot)) {
            return Optional.empty();
        }
        if (Files.notExists(canonicalRoot, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(candidate);
        }
        Path resolvedCandidate = resolveThroughNearestExistingAncestor(candidate);
        Path resolvedRoot = realPathOf(canonicalRoot).orElse(canonicalRoot);
        return resolvedCandidate.startsWith(resolvedRoot) ? Optional.of(resolvedCandidate) : Optional.empty();
    }

    public static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(StagedDirectoryInstall::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort staging cleanup after a failed install
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

    private static Path canonicalize(Path root) {
        return realPathOf(root).orElseGet(() -> root.toAbsolutePath().normalize());
    }

    private static Optional<Path> realPathOf(Path path) {
        try {
            return Optional.of(path.toRealPath());
        } catch (IOException | SecurityException unresolvable) {
            return Optional.empty();
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
