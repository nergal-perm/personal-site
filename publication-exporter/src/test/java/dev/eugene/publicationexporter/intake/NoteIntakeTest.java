package dev.eugene.publicationexporter.intake;

import dev.eugene.publicationexporter.admission.PublicationKinds;
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
            title: My Essay
            description: A valid description.
            ---
            # Body""";

    private final NoteIntake intake = new NoteIntake(PublicationKinds.installed());

    @Test
    void unpublishedEssayIsBlocked() {
        String unpublished = VALID_ESSAY.replace("publish: true\n", "");
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, unpublished)));

        assertFalse(result.accepted());
        assertEquals("publish", result.diagnostics().get(0).field());
    }

    @Test
    void unsupportedCollectionContentTypePairIsBlocked() {
        String wrongCollection = VALID_ESSAY.replace("publicCollection: blog", "publicCollection: bibliography");
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, wrongCollection)));

        assertFalse(result.accepted());
        assertEquals("publicContentType", result.diagnostics().get(0).field());
    }

    @Test
    void unsupportedContentTypeIsBlocked() {
        String wrongContentType = VALID_ESSAY.replace("publicContentType: essay", "publicContentType: claim");
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, wrongContentType)));

        assertFalse(result.accepted());
        assertEquals("publicContentType", result.diagnostics().get(0).field());
    }

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
                title: My Essay
                description: A valid description.
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
        assertEquals("my-essay", result.identity().publicId());
        assertEquals("# Body", result.body());
    }

    @Test
    void acceptedIntakeExposesTitleAndDescription() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, VALID_ESSAY)));

        assertTrue(result.accepted());
        assertEquals("My Essay", result.title());
        assertEquals("A valid description.", result.description());
    }

    @Test
    void acceptedIntakeExposesArbitraryFrontmatterScalarsFromItsParsedSource() {
        String essayWithWorkflowStatus = VALID_ESSAY.replace(
                "description: A valid description.",
                "description: A valid description.\nworkflowStatus: ready_for_review");
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(
                path, VaultReader.createNull(Map.of(path, essayWithWorkflowStatus)));

        assertTrue(result.accepted());
        assertEquals("ready_for_review", result.frontmatterString("workflowStatus").orElseThrow());
        assertTrue(result.frontmatterString("missingScalar").isEmpty());
    }
}
