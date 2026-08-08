package dev.eugene.publicationexporter.workflow;

import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemWorkflowStatusEditorTest {

    @TempDir
    Path vaultRoot;

    private static final String SOURCE = "---\npublish: true\npublicId: my-essay\n---\n# Title\n\nBody тест.";

    @Test
    void writeUpdatesOnlyTheDeclaredKeyAndPreservesEveryOtherByte() throws Exception {
        Path note = writeNote("blog/my-essay.md", SOURCE);
        FilesystemWorkflowStatusEditor editor = new FilesystemWorkflowStatusEditor(vaultRoot);

        WorkflowStatusEditor.Result result = editor.write(
                VaultRelativePath.of("blog/my-essay.md"), ContentHash.sha256Hex(SOURCE), "ready_for_review");

        assertTrue(result.isWritten());
        String updated = Files.readString(note, StandardCharsets.UTF_8);
        assertEquals("---\npublish: true\npublicId: my-essay\nworkflowStatus: ready_for_review\n---\n"
                + "# Title\n\nBody тест.", updated);
    }

    @Test
    void writeBlocksWithoutTouchingTheFileWhenHashDoesNotMatch() throws Exception {
        Path note = writeNote("blog/my-essay.md", SOURCE);
        FilesystemWorkflowStatusEditor editor = new FilesystemWorkflowStatusEditor(vaultRoot);

        WorkflowStatusEditor.Result result = editor.write(
                VaultRelativePath.of("blog/my-essay.md"), "stale-hash", "ready_for_review");

        assertFalse(result.isWritten());
        assertEquals(SOURCE, Files.readString(note, StandardCharsets.UTF_8));
    }

    @Test
    void writePreservesPosixPermissions() throws Exception {
        Path note = writeNote("blog/my-essay.md", SOURCE);
        Set<PosixFilePermission> restrictive = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(note, restrictive);
        FilesystemWorkflowStatusEditor editor = new FilesystemWorkflowStatusEditor(vaultRoot);

        editor.write(VaultRelativePath.of("blog/my-essay.md"), ContentHash.sha256Hex(SOURCE), "stale");

        assertEquals(restrictive, Files.getPosixFilePermissions(note));
    }

    private Path writeNote(String relativePath, String source) throws Exception {
        Path note = vaultRoot.resolve(relativePath);
        Files.createDirectories(note.getParent());
        Files.writeString(note, source, StandardCharsets.UTF_8);
        return note;
    }
}
