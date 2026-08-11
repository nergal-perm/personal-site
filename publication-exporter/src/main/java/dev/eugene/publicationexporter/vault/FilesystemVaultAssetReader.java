package dev.eugene.publicationexporter.vault;

import java.nio.file.Path;
import java.util.Objects;

final class FilesystemVaultAssetReader implements VaultAssetReader {

    private final Path vaultRoot;

    FilesystemVaultAssetReader(Path vaultRoot) {
        this.vaultRoot = Objects.requireNonNull(vaultRoot, "vaultRoot");
    }

    @Override
    public AssetLookup resolve(String reference) {
        throw new UnsupportedOperationException("Implemented in section 6");
    }
}
