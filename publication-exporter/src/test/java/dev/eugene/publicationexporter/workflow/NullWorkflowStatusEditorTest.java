package dev.eugene.publicationexporter.workflow;

import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullWorkflowStatusEditorTest {

    private static final VaultRelativePath PATH = VaultRelativePath.of("blog/my-essay.md");
    private static final String SOURCE = "---\npublish: true\n---\nBody.";

    @Test
    void writeSucceedsWhenExpectedHashMatchesSeededSource() {
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(PATH, SOURCE));

        WorkflowStatusEditor.Result result =
                editor.write(PATH, ContentHash.sha256Hex(SOURCE), "ready_for_review");

        assertTrue(result.isWritten());
        assertEquals("ready_for_review", editor.currentValue(PATH, "workflowStatus"));
    }

    @Test
    void writeBlocksWhenExpectedHashDoesNotMatch() {
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(PATH, SOURCE));

        WorkflowStatusEditor.Result result = editor.write(PATH, "stale-hash", "ready_for_review");

        assertFalse(result.isWritten());
        assertEquals("Source changed since it was validated.", result.blockedReason());
    }

    @Test
    void writeBlocksWhenTheNoteIsMissing() {
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor();

        WorkflowStatusEditor.Result result =
                editor.write(PATH, ContentHash.sha256Hex(SOURCE), "stale");

        assertFalse(result.isWritten());
        assertEquals("Source changed since it was validated.", result.blockedReason());
    }
}
