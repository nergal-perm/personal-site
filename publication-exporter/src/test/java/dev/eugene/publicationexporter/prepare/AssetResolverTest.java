package dev.eugene.publicationexporter.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.eugene.publicationexporter.vault.VaultAssetReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AssetResolverTest {

    private static String resolvedBodyOrFail(String body, VaultAssetReader vaultAssetReader) {
        return AssetResolver.resolve(body, vaultAssetReader).resolve(
                (resolvedBody, assets) -> resolvedBody,
                reference -> fail("Expected a resolved result but \"" + reference + "\" was blocked."));
    }

    @Test
    void aliasWinsOverBasenameAsTheLabel() {
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of("a/diagram.png", bytes));
        String digest = dev.eugene.publicationexporter.hash.ContentHash.sha256Hex(bytes);

        assertEquals("![a great diagram](/assets/vault/" + digest + ".png)",
                resolvedBodyOrFail("![[a/diagram.png|a great diagram]]", vaultAssetReader));
    }

    @Test
    void extensionMatchingAndOutputNamingAreCaseInsensitiveWithJpegCanonicalizedToJpg() {
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of("Photo.JPEG", bytes));
        String digest = dev.eugene.publicationexporter.hash.ContentHash.sha256Hex(bytes);

        assertEquals("![Photo](/assets/vault/" + digest + ".jpg)",
                resolvedBodyOrFail("![[Photo.JPEG]]", vaultAssetReader));
    }

    @Test
    void twoDifferentExtensionReferencesWithIdenticalBytesBlockOnTheSecondOne() {
        byte[] sharedBytes = "same-bytes".getBytes(StandardCharsets.UTF_8);
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of(
                "cover.png", sharedBytes, "cover.gif", sharedBytes));
        String body = "![[cover.png]] then ![[cover.gif]]";

        String blockedReference = AssetResolver.resolve(body, vaultAssetReader).resolve(
                (resolvedBody, assets) -> fail("Expected a block but resolution succeeded: " + resolvedBody),
                reference -> reference);

        assertEquals("cover.gif", blockedReference);
    }

    @Test
    void assetEmbedLikeTextInsideInlineCodeIsNeverResolved() {
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull();
        String body = "Example: `![[diagram.png]]` is embed syntax.";

        assertEquals(body, resolvedBodyOrFail(body, vaultAssetReader));
    }

    @Test
    void ambiguousBasenameReportsTheOffendingReferenceText() {
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of(
                "a/logo.png", new byte[] {1}, "b/logo.png", new byte[] {2}));
        String body = "![[logo.png]]";

        String blockedReference = AssetResolver.resolve(body, vaultAssetReader).resolve(
                (resolvedBody, assets) -> fail("Expected a block but resolution succeeded: " + resolvedBody),
                reference -> reference);

        assertEquals("logo.png", blockedReference);
    }
}
