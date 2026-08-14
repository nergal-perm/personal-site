package dev.eugene.publicationexporter.vault;

import dev.eugene.publicationexporter.note.MarkdownNote;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Defends against structural path traversal and stable symlinks that escape the vault, but not against an adversary
 * with concurrent write access racing path validation against the subsequent read; path-based filesystem APIs cannot
 * portably provide descriptor-anchored opening, so a TOCTOU window remains. This matches the project's threat model of
 * a single local user exporting their own vault, not multi-tenant or concurrently adversarial writes.
 */
final class FilesystemVaultReader implements VaultReader {

    private final Path canonicalVaultRoot;

    FilesystemVaultReader(Path vaultRoot) {
        this.canonicalVaultRoot = canonicalize(Objects.requireNonNull(vaultRoot, "vaultRoot"));
    }

    @Override
    public boolean exists(VaultRelativePath notePath) {
        return resolveWithinVault(notePath).isPresent();
    }

    @Override
    public String readSource(VaultRelativePath notePath) {
        Path real = resolveWithinVault(notePath)
                .orElseThrow(() -> new NoSuchElementException("Note not found: " + notePath.value()));
        return readUtf8(real);
    }

    @Override
    public List<VaultRelativePath> listPublishCandidates() {
        return listMarkdownFiles().stream()
                .filter(this::hasPublishTrueFlag)
                .map(ConfinedCandidate::originalPath)
                .map(this::toVaultRelativePath)
                .sorted(Comparator.comparing(VaultRelativePath::value))
                .toList();
    }

    @Override
    public List<VaultRelativePath> listAllNotePaths() {
        return listMarkdownFiles().stream()
                .map(ConfinedCandidate::originalPath)
                .map(this::toVaultRelativePath)
                .sorted(Comparator.comparing(VaultRelativePath::value))
                .toList();
    }

    private List<ConfinedCandidate> listMarkdownFiles() {
        try (var paths = Files.walk(canonicalVaultRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .map(this::confinedCandidate)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Optional<ConfinedCandidate> confinedCandidate(Path originalPath) {
        return realPathOf(originalPath)
                .filter(this::isInsideVault)
                .map(realPath -> new ConfinedCandidate(originalPath, realPath));
    }

    private boolean hasPublishTrueFlag(ConfinedCandidate candidate) {
        try {
            return MarkdownNote.parse(readUtf8(candidate.realPath())).flag("publish");
        } catch (UncheckedIOException unreadable) {
            return false;
        }
    }

    private VaultRelativePath toVaultRelativePath(Path file) {
        return VaultRelativePath.of(canonicalVaultRoot.relativize(file).toString().replace('\\', '/'));
    }

    private Optional<Path> resolveWithinVault(VaultRelativePath notePath) {
        return candidateFor(notePath)
                .flatMap(FilesystemVaultReader::realPathOf)
                .filter(this::isInsideVault)
                .filter(Files::isRegularFile);
    }

    private static String readUtf8(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Optional<Path> candidateFor(VaultRelativePath notePath) {
        try {
            return Optional.of(canonicalVaultRoot.resolve(notePath.value()));
        } catch (InvalidPathException unrepresentable) {
            return Optional.empty();
        }
    }

    private boolean isInsideVault(Path realNotePath) {
        return realNotePath.startsWith(canonicalVaultRoot);
    }

    private static Path canonicalize(Path vaultRoot) {
        return realPathOf(vaultRoot).orElseGet(() -> vaultRoot.toAbsolutePath().normalize());
    }

    private static Optional<Path> realPathOf(Path path) {
        try {
            return Optional.of(path.toRealPath());
        } catch (IOException | SecurityException unresolvable) {
            return Optional.empty();
        }
    }

    private record ConfinedCandidate(Path originalPath, Path realPath) {
    }
}
