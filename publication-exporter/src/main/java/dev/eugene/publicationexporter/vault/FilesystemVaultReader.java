package dev.eugene.publicationexporter.vault;

import java.nio.file.Files;
import java.nio.file.Path;

final class FilesystemVaultReader implements VaultReader {

    private final Path vaultRoot;

    FilesystemVaultReader(Path vaultRoot) {
        this.vaultRoot = vaultRoot;
    }

    @Override
    public boolean exists(VaultRelativePath notePath) {
        return Files.exists(vaultRoot.resolve(notePath.value()));
    }
}
