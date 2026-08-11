package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.note.MarkdownNote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotePublicationKindTest {

    private final NotePublicationKind admission = new NotePublicationKind();

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.eugene.publicationexporter.admission.NotePublicationKindFixtures#all")
    void admitsOrBlocksPerFixture(NotePublicationKindFixture fixture) {
        MarkdownNote frontmatter = MarkdownNote.parse(fixture.noteSource());

        AdmittedPublication result = admission.admit(frontmatter);

        assertEquals(fixture.expectedAccepted(), result.accepted(), fixture.name());
        if (!fixture.expectedAccepted()) {
            assertEquals(fixture.expectedBlockedFields(), blockedFields(result), fixture.name());
        }
    }

    @Test
    void validNoteResultCarriesIdentityAndFields() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: note
                publicId: my-note
                id: 8f2c-my-note
                title: My Note
                description: A valid description.
                ---
                A short observation body.""");

        AdmittedPublication result = admission.admit(frontmatter);

        assertTrue(result.accepted());
        assertEquals(PublicationIdentity.of("blog", "note", "my-note"), result.identity());
        assertEquals("8f2c-my-note", result.sourceId());
        assertEquals("My Note", result.title());
        assertEquals("A valid description.", result.description());
    }

    private List<String> blockedFields(AdmittedPublication result) {
        return result.diagnostics().stream().map(Diagnostic::field).toList();
    }
}
