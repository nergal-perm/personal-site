package dev.eugene.publicationexporter.vault;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

final class NullVaultReader implements VaultReader {

    private final Set<String> existingPaths;

    NullVaultReader(VaultRelativePath... existing) {
        this.existingPaths = Arrays.stream(existing)
                .map(VaultRelativePath::value)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean exists(VaultRelativePath notePath) {
        return existingPaths.contains(notePath.value());
    }
}
