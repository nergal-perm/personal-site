package dev.eugene.publicationexporter.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void listPublishCandidatesWalksTheVaultAndFiltersByPublishFlag(@TempDir Path vaultRoot) throws Exception {
        writeNote(vaultRoot, "blog/my-essay.md", "---\npublish: true\n---\nBody.");
        writeNote(vaultRoot, "blog/draft.md", "---\npublish: false\n---\nBody.");
        writeNote(vaultRoot, "scratch/todo.md", "No frontmatter here.");
        VaultReader vaultReader = VaultReader.create(vaultRoot);

        assertEquals(List.of(VaultRelativePath.of("blog/my-essay.md")), vaultReader.listPublishCandidates());
    }

    @Test
    void listPublishCandidatesExcludesSymlinkEscapingTheVaultRoot() throws Exception {
        Path externalPublishedNote = writeNote(
                outsideVaultRoot, "published.md", "---\npublish: true\n---\nOutside body.");
        Files.createDirectories(vaultRoot.resolve("blog"));
        try {
            Files.createSymbolicLink(vaultRoot.resolve("blog/linked.md"), externalPublishedNote);
        } catch (IOException | UnsupportedOperationException unsupported) {
            Assumptions.abort("Symbolic links are unavailable: " + unsupported.getMessage());
        }

        VaultReader vaultReader = VaultReader.create(vaultRoot);

        assertEquals(List.of(), vaultReader.listPublishCandidates());
    }

    private Path writeNote(Path vaultRoot, String relativePath, String source) throws IOException {
        Path note = vaultRoot.resolve(relativePath);
        Files.createDirectories(note.getParent());
        Files.writeString(note, source, StandardCharsets.UTF_8);
        return note;
    }
}
