package dev.eugene.publicationexporter.vault;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
        return candidateFor(notePath)
                .flatMap(FilesystemVaultReader::realPathOf)
                .filter(this::isInsideVault)
                .isPresent();
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
