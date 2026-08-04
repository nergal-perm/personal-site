package dev.eugene.publicationexporter.intake;

import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteIntakeTest {

    private static final String VALID_ESSAY = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            ---
            # Body""";

    private final NoteIntake intake = new NoteIntake();

    @Test
    void unsafePathIsBlocked() {
        NoteIntake.Result result = intake.admit(
                VaultRelativePath.of("../../etc/passwd.md"), VaultReader.createNull());

        assertFalse(result.accepted());
        assertEquals("Note path escapes the vault root.", result.diagnostics().get(0).message());
    }

    @Test
    void nonMarkdownPathIsBlocked() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.txt");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, VALID_ESSAY)));

        assertFalse(result.accepted());
        assertEquals("Note path must name a Markdown file.", result.diagnostics().get(0).message());
    }

    @Test
    void missingNoteIsBlocked() {
        NoteIntake.Result result = intake.admit(
                VaultRelativePath.of("blog/does-not-exist.md"), VaultReader.createNull());

        assertFalse(result.accepted());
        assertEquals("Note was not found in the vault.", result.diagnostics().get(0).message());
    }

    @Test
    void malformedEssayIsBlockedWithAdmissionDiagnostics() {
        String missingSourceId = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                ---
                # Body""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, missingSourceId)));

        assertFalse(result.accepted());
        assertEquals("id", result.diagnostics().get(0).field());
    }

    @Test
    void validEssayIsAcceptedWithAdmissionAndFrontmatterExposed() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, VALID_ESSAY)));

        assertTrue(result.accepted());
        assertEquals("my-essay", result.admission().identity().publicId());
        assertEquals("# Body", result.frontmatter().body());
    }
}
