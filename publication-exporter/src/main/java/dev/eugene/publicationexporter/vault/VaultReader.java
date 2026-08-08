package dev.eugene.publicationexporter.vault;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface VaultReader {

    boolean exists(VaultRelativePath notePath);

    String readSource(VaultRelativePath notePath);

    List<VaultRelativePath> listPublishCandidates();

    static VaultReader create(Path vaultRoot) {
        return new FilesystemVaultReader(vaultRoot);
    }

    static VaultReader createNull(VaultRelativePath... existingPaths) {
        return new NullVaultReader(existingPaths);
    }

    static VaultReader createNull(Map<VaultRelativePath, String> notesBySource) {
        return new NullVaultReader(notesBySource);
    }
}
