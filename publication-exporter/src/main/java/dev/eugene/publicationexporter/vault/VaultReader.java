package dev.eugene.publicationexporter.vault;

public interface VaultReader {

    boolean exists(VaultRelativePath notePath);

    static VaultReader createNull(VaultRelativePath... existingPaths) {
        return new NullVaultReader(existingPaths);
    }
}
