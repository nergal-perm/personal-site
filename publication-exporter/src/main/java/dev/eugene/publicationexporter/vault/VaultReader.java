package dev.eugene.publicationexporter.vault;

import java.nio.file.Path;

public interface VaultReader {

    boolean exists(VaultRelativePath notePath);

    static VaultReader create(Path vaultRoot) {
        return new FilesystemVaultReader(vaultRoot);
    }

    static VaultReader createNull(VaultRelativePath... existingPaths) {
        return new NullVaultReader(existingPaths);
    }
}
