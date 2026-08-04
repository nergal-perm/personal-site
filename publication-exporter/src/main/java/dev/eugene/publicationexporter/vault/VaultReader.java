package dev.eugene.publicationexporter.vault;

import java.util.Map;

public interface VaultReader {

    boolean exists(VaultRelativePath notePath);

    String readSource(VaultRelativePath notePath);

    static VaultReader create(java.nio.file.Path vaultRoot) {
        return new FilesystemVaultReader(vaultRoot);
    }

    static VaultReader createNull(VaultRelativePath... existingPaths) {
        return new NullVaultReader(existingPaths);
    }

    static VaultReader createNull(Map<VaultRelativePath, String> notesBySource) {
        return new NullVaultReader(notesBySource);
    }
}
