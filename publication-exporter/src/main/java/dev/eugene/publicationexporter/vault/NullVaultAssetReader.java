package dev.eugene.publicationexporter.vault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class NullVaultAssetReader implements VaultAssetReader {

    private final Map<String, byte[]> contentByPath;

    NullVaultAssetReader(Map<String, byte[]> assetsByVaultRelativePath) {
        Objects.requireNonNull(assetsByVaultRelativePath, "assetsByVaultRelativePath");
        this.contentByPath = Map.copyOf(assetsByVaultRelativePath);
    }

    @Override
    public AssetLookup resolve(String reference) {
        Objects.requireNonNull(reference, "reference");
        if (!VaultRelativePath.of(reference).isWithinVault()) {
            return AssetLookup.unsafe();
        }
        byte[] exact = contentByPath.get(reference);
        if (exact != null) {
            return AssetLookup.found(exact);
        }
        return resolveByBasename(basename(reference));
    }

    private AssetLookup resolveByBasename(String basename) {
        List<byte[]> matches = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : contentByPath.entrySet()) {
            if (basename(entry.getKey()).equals(basename)) {
                matches.add(entry.getValue());
            }
        }
        if (matches.isEmpty()) {
            return AssetLookup.notFound();
        }
        if (matches.size() > 1) {
            return AssetLookup.ambiguous();
        }
        return AssetLookup.found(matches.get(0));
    }

    private static String basename(String reference) {
        int lastSlash = reference.lastIndexOf('/');
        return lastSlash >= 0 ? reference.substring(lastSlash + 1) : reference;
    }
}
