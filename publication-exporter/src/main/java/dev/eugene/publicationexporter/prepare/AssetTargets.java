package dev.eugene.publicationexporter.prepare;

import java.util.Locale;
import java.util.Set;

final class AssetTargets {

    private static final Set<String> ASSET_EXTENSIONS =
            Set.of(".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".mp3", ".mp4");

    private AssetTargets() {
    }

    static boolean isAssetTarget(String target) {
        String lowercaseTarget = target.toLowerCase(Locale.ROOT);
        return ASSET_EXTENSIONS.stream().anyMatch(lowercaseTarget::endsWith);
    }
}
