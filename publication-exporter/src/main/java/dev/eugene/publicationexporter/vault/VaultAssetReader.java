package dev.eugene.publicationexporter.vault;

import java.nio.file.Path;
import java.util.Map;

public interface VaultAssetReader {

    AssetLookup resolve(String reference);

    static VaultAssetReader create(Path vaultRoot) {
        return new FilesystemVaultAssetReader(vaultRoot);
    }

    static VaultAssetReader createNull() {
        return new NullVaultAssetReader(Map.of());
    }

    static VaultAssetReader createNull(Map<String, byte[]> assetsByVaultRelativePath) {
        return new NullVaultAssetReader(assetsByVaultRelativePath);
    }
}
