package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.candidate.CandidateAsset;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.vault.VaultAssetReader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AssetResolver {

    private static final Pattern ASSET_EMBED =
            Pattern.compile("!\\[\\[([^\\[\\]|#]+)(?:#[^\\[\\]|]*)?(?:\\|([^\\[\\]]+))?]]");

    private AssetResolver() {
    }

    public static AssetResolutionOutcome resolve(String body, VaultAssetReader vaultAssetReader) {
        StringBuilder output = new StringBuilder(body.length());
        Map<String, CandidateAsset> assetsByDigest = new LinkedHashMap<>();
        int cursor = 0;
        while (cursor < body.length()) {
            ProtectedRegionScanner.ProtectedSpan protectedSpan = ProtectedRegionScanner.nextProtectedSpan(body, cursor);
            Matcher embed = nextAssetEmbed(body, cursor);
            if (protectedSpanBeforeEmbed(protectedSpan, embed)) {
                cursor = copyProtectedSpan(body, output, cursor, protectedSpan);
            } else if (embed != null) {
                Optional<String> blockedReference =
                        appendAsset(body, output, cursor, embed, vaultAssetReader, assetsByDigest);
                if (blockedReference.isPresent()) {
                    return AssetResolutionOutcome.blocked(blockedReference.get());
                }
                cursor = embed.end();
            } else {
                break;
            }
        }
        output.append(body, cursor, body.length());
        return AssetResolutionOutcome.resolved(output.toString(), List.copyOf(assetsByDigest.values()));
    }

    private static Matcher nextAssetEmbed(String body, int cursor) {
        Matcher matcher = ASSET_EMBED.matcher(body);
        int searchFrom = cursor;
        while (matcher.find(searchFrom)) {
            if (AssetTargets.isAssetTarget(matcher.group(1).strip())) {
                return matcher;
            }
            searchFrom = matcher.end();
        }
        return null;
    }

    private static boolean protectedSpanBeforeEmbed(ProtectedRegionScanner.ProtectedSpan protectedSpan, Matcher embed) {
        return protectedSpan != null && (embed == null || protectedSpan.start() <= embed.start());
    }

    private static int copyProtectedSpan(
            String body, StringBuilder output, int cursor, ProtectedRegionScanner.ProtectedSpan protectedSpan) {
        output.append(body, cursor, protectedSpan.end());
        return protectedSpan.end();
    }

    private static Optional<String> appendAsset(
            String body, StringBuilder output, int cursor, Matcher embed,
            VaultAssetReader vaultAssetReader, Map<String, CandidateAsset> assetsByDigest) {
        output.append(body, cursor, embed.start());
        String reference = embed.group(1).strip();
        String label = labelFor(embed, reference);
        return vaultAssetReader.resolve(reference).resolve(
                content -> appendMaterialized(output, reference, label, content, assetsByDigest),
                () -> Optional.of(reference),
                () -> Optional.of(reference),
                () -> Optional.of(reference));
    }

    private static Optional<String> appendMaterialized(
            StringBuilder output, String reference, String label, byte[] content,
            Map<String, CandidateAsset> assetsByDigest) {
        String digest = ContentHash.sha256Hex(content);
        String extension = extensionOf(reference);
        CandidateAsset existing = assetsByDigest.get(digest);
        if (existing != null && !existing.publicName().endsWith(extension)) {
            return Optional.of(reference);
        }
        CandidateAsset asset = existing != null
                ? existing
                : assetsByDigest.computeIfAbsent(digest, ignored -> CandidateAsset.of(digest + extension, content));
        output.append("![").append(label).append("](/assets/vault/").append(asset.publicName()).append(')');
        return Optional.empty();
    }

    private static String labelFor(Matcher embed, String reference) {
        String alias = embed.group(2);
        return alias != null ? alias.strip() : lastPathSegment(reference);
    }

    private static String lastPathSegment(String reference) {
        int lastSlash = reference.lastIndexOf('/');
        String segment = lastSlash >= 0 ? reference.substring(lastSlash + 1) : reference;
        int dot = segment.lastIndexOf('.');
        return dot > 0 ? segment.substring(0, dot) : segment;
    }

    private static String extensionOf(String reference) {
        int dot = reference.lastIndexOf('.');
        String extension = dot < 0 ? "" : reference.substring(dot).toLowerCase(Locale.ROOT);
        return extension.equals(".jpeg") ? ".jpg" : extension;
    }
}
