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

class EssayAdmissionTest {

    private final EssayAdmission admission = new EssayAdmission();

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.eugene.publicationexporter.admission.EssayAdmissionFixtures#all")
    void admitsOrBlocksPerFixture(EssayAdmissionFixture fixture) {
        MarkdownNote frontmatter = MarkdownNote.parse(fixture.noteSource());

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertEquals(fixture.expectedAccepted(), result.accepted(), fixture.name());
        if (!fixture.expectedAccepted()) {
            assertEquals(fixture.expectedBlockedFields(), blockedFields(result), fixture.name());
        }
    }

    @Test
    void validEssayResultCarriesIdentityAndFields() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertTrue(result.accepted());
        assertEquals(PublicationIdentity.of("blog", "essay", "my-essay"), result.identity());
        assertEquals("8f2c-my-essay", result.sourceId());
        assertEquals("My Essay", result.title());
        assertEquals("A valid description.", result.description());
    }

    private List<String> blockedFields(EssayAdmission.Result result) {
        return result.diagnostics().stream().map(Diagnostic::field).toList();
    }
}
