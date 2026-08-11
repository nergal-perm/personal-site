package dev.eugene.publicationexporter.vault;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FilesystemVaultAssetReaderTest {

    @TempDir
    Path vaultRoot;

    private static byte[] foundOrFail(AssetLookup lookup) {
        return lookup.resolve(
                content -> content,
                () -> fail("Expected found but got ambiguous"),
                () -> fail("Expected found but got unsafe"),
                () -> fail("Expected found but got notFound"));
    }

    @Test
    void exactVaultRelativePathIsPreferredOverAnotherFileWithTheSameBasename() throws IOException {
        Files.createDirectories(vaultRoot.resolve("assets"));
        Files.writeString(vaultRoot.resolve("assets/logo.png"), "assets-copy", StandardCharsets.UTF_8);
        Files.createDirectories(vaultRoot.resolve("archive"));
        Files.writeString(vaultRoot.resolve("archive/logo.png"), "archive-copy", StandardCharsets.UTF_8);
        VaultAssetReader reader = VaultAssetReader.create(vaultRoot);

        AssetLookup lookup = reader.resolve("assets/logo.png");

        assertArrayEquals("assets-copy".getBytes(StandardCharsets.UTF_8), foundOrFail(lookup));
    }

    @Test
    void uniqueBasenameFallsBackWhenNoExactMatchExists() throws IOException {
        Files.createDirectories(vaultRoot.resolve("nested"));
        Files.writeString(vaultRoot.resolve("nested/only-copy.png"), "content", StandardCharsets.UTF_8);
        VaultAssetReader reader = VaultAssetReader.create(vaultRoot);

        AssetLookup lookup = reader.resolve("only-copy.png");

        assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), foundOrFail(lookup));
    }

    @Test
    void ambiguousBasenameIsBlockedWhenNoExactMatchExists() throws IOException {
        Files.createDirectories(vaultRoot.resolve("a"));
        Files.createDirectories(vaultRoot.resolve("b"));
        Files.writeString(vaultRoot.resolve("a/dup.png"), "one", StandardCharsets.UTF_8);
        Files.writeString(vaultRoot.resolve("b/dup.png"), "two", StandardCharsets.UTF_8);
        VaultAssetReader reader = VaultAssetReader.create(vaultRoot);

        AssetLookup lookup = reader.resolve("dup.png");

        String outcome = lookup.resolve(
                content -> "found",
                () -> "ambiguous",
                () -> "unsafe",
                () -> "notFound");
        assertEquals("ambiguous", outcome);
    }

    @Test
    void traversalOutsideTheVaultIsBlockedAsUnsafe() {
        VaultAssetReader reader = VaultAssetReader.create(vaultRoot);

        AssetLookup lookup = reader.resolve("../outside.png");

        String outcome = lookup.resolve(
                content -> "found", () -> "ambiguous", () -> "unsafe", () -> "notFound");
        assertEquals("unsafe", outcome);
    }

    @Test
    void symlinkEscapingTheVaultIsBlockedAsUnsafe() throws IOException {
        Path outside = Files.createTempDirectory("outside-vault");
        Path outsideFile = Files.writeString(outside.resolve("secret.png"), "secret", StandardCharsets.UTF_8);
        Files.createSymbolicLink(vaultRoot.resolve("escape.png"), outsideFile);
        VaultAssetReader reader = VaultAssetReader.create(vaultRoot);

        AssetLookup lookup = reader.resolve("escape.png");

        String outcome = lookup.resolve(
                content -> "found", () -> "ambiguous", () -> "unsafe", () -> "notFound");
        assertEquals("notFound", outcome);
    }

    @Test
    void missingReferenceIsNotFound() {
        VaultAssetReader reader = VaultAssetReader.create(vaultRoot);

        AssetLookup lookup = reader.resolve("nowhere.png");

        String outcome = lookup.resolve(
                content -> "found", () -> "ambiguous", () -> "unsafe", () -> "notFound");
        assertEquals("notFound", outcome);
    }
}
