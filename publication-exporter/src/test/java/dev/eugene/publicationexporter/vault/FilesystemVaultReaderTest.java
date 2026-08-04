package dev.eugene.publicationexporter.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemVaultReaderTest {

    @TempDir
    Path vaultRoot;

    @TempDir
    Path outsideVaultRoot;

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
    void reportsFalseForDirectoryAtSafePath() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog/directory.md"));

        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertFalse(reader.exists(VaultRelativePath.of("blog/directory.md")));
    }

    @Test
    void reportsFalseForSymlinkEscapingTheVaultRoot() throws Exception {
        Path secret = Files.writeString(
                outsideVaultRoot.resolve("secret.md"), "# Outside the vault");
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.createSymbolicLink(vaultRoot.resolve("blog/link.md"), secret);

        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertFalse(reader.exists(VaultRelativePath.of("blog/link.md")));
    }

    @Test
    void reportsFalseForSymlinkedDirectoryEscapingTheVaultRoot() throws Exception {
        Files.writeString(outsideVaultRoot.resolve("secret.md"), "# Outside the vault");
        Files.createSymbolicLink(vaultRoot.resolve("blog"), outsideVaultRoot);

        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertFalse(reader.exists(VaultRelativePath.of("blog/secret.md")));
    }

    @Test
    void reportsTrueForSymlinkResolvingInsideTheVaultRoot() throws Exception {
        Files.createDirectories(vaultRoot.resolve("notes"));
        Path target = Files.writeString(vaultRoot.resolve("notes/target.md"), "# Inside the vault");
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.createSymbolicLink(vaultRoot.resolve("blog/alias.md"), target);

        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertTrue(reader.exists(VaultRelativePath.of("blog/alias.md")));
    }

    @Test
    void reportsFalseForBrokenSymlink() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.createSymbolicLink(vaultRoot.resolve("blog/dangling.md"), vaultRoot.resolve("notes/gone.md"));

        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertFalse(reader.exists(VaultRelativePath.of("blog/dangling.md")));
    }

    @Test
    void reportsFalseForPathTheFilesystemCannotRepresent() {
        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        String pathWithNulCharacter = "blog/nul\0byte.md";

        assertFalse(reader.exists(VaultRelativePath.of(pathWithNulCharacter)));
    }

    @Test
    void interfaceFactoryDelegatesToRealAdapter() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/real-note.md"), "# Real note");

        VaultReader reader = VaultReader.create(vaultRoot);
        assertTrue(reader.exists(VaultRelativePath.of("blog/real-note.md")));
    }

    @Test
    void readSourceReturnsRealFileContent() throws Exception {
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.writeString(vaultRoot.resolve("blog/real-note.md"), "---\npublish: true\n---\n");

        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertEquals("---\npublish: true\n---\n",
                reader.readSource(VaultRelativePath.of("blog/real-note.md")));
    }

    @Test
    void readSourceThrowsForMissingFile() {
        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertThrows(NoSuchElementException.class,
                () -> reader.readSource(VaultRelativePath.of("blog/missing.md")));
    }

    @Test
    void readSourceThrowsForSymlinkEscapingTheVaultRoot() throws Exception {
        Path secret = Files.writeString(
                outsideVaultRoot.resolve("secret.md"), "# Outside the vault");
        Files.createDirectories(vaultRoot.resolve("blog"));
        Files.createSymbolicLink(vaultRoot.resolve("blog/link.md"), secret);

        FilesystemVaultReader reader = new FilesystemVaultReader(vaultRoot);
        assertThrows(NoSuchElementException.class,
                () -> reader.readSource(VaultRelativePath.of("blog/link.md")));
    }
}
