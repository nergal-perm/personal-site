package dev.eugene.publicationexporter.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemVaultReaderTest {

    @TempDir
    Path vaultRoot;

    @Test
    void reportsTrueForRealFile() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/real-note.md"), "# Real note");

        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertTrue(reader.exists(VaultRelativePath.of("blog/real-note.md")));
    }

    @Test
    void reportsFalseForMissingFile() {
        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertFalse(reader.exists(VaultRelativePath.of("blog/missing.md")));
    }

    @Test
    void interfaceFactoryDelegatesToRealAdapter() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/real-note.md"), "# Real note");

        VaultReader reader = VaultReader.create(vaultRoot);
        assertTrue(reader.exists(VaultRelativePath.of("blog/real-note.md")));
    }
}
