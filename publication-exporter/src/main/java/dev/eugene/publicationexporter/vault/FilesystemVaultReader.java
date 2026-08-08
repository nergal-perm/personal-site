package dev.eugene.publicationexporter.vault;

import dev.eugene.publicationexporter.note.Frontmatter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

final class FilesystemVaultReader implements VaultReader {

    private final Path canonicalVaultRoot;

    FilesystemVaultReader(Path vaultRoot) {
        this.canonicalVaultRoot = canonicalize(Objects.requireNonNull(vaultRoot, "vaultRoot"));
    }

    /**
     * Reports whether the note really exists <em>inside</em> the vault. A path that resolves —
     * through symbolic links — to a location outside the canonical vault root is reported as
     * absent, so a link planted in the vault cannot expose an external file.
     */
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
        try (var paths = Files.walk(canonicalVaultRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> realPathOf(path).filter(this::isInsideVault).isPresent())
                    .filter(this::hasPublishTrueFlag)
                    .map(this::toVaultRelativePath)
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private boolean hasPublishTrueFlag(Path file) {
        try {
            return Frontmatter.parse(readUtf8(file)).flag("publish");
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
}
