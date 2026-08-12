package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookPublicationKindTest {

    private final BookPublicationKind admission = new BookPublicationKind();

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.eugene.publicationexporter.admission.BookPublicationKindFixtures#all")
    void admitsOrBlocksPerFixture(BookPublicationKindFixture fixture) {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(fixture.noteSource()));

        assertEquals(fixture.expectedAccepted(), result.accepted(), fixture.name());
        if (!fixture.expectedAccepted()) {
            assertEquals(fixture.expectedBlockedFields(), blockedFields(result), fixture.name());
        }
    }

    @Test
    void validBookOwnsBibliographyIdentityAndLibraryRoute() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(
                fixtureNamed("validBook").noteSource()));

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals(PublicationIdentity.of("bibliography", "book", "the-lean-startup"), result.identity());
        assertEquals("library", admission.routePrefix());
    }

    @Test
    void selectedQuoteDiagnosticExplainsWhyTheBookIsBlocked() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(
                fixtureNamed("selectedQuote").noteSource()));

        assertTrue(result.diagnostics().get(0).message()
                .contains("mixed translated structured quote metadata is not supported by this slice"));
    }

    @Test
    void validBookSeparatesTranslatedAndInvariantMetadataDeterministically() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse("""
                ---
                publish: true
                publicCollection: bibliography
                publicContentType: book
                publicId: the-lean-startup
                id: 8f2c-the-lean-startup
                title: The Lean Startup
                description: A valid description.
                authors:
                  - Eric Ries
                  - "Cindy Alvarez"
                publication: Crown Business
                publicationDate: 2011-09-13
                start: 2026-01-10
                end: 2026-01-20
                readingStatus: finished
                use: Explains how to test demand before scaling a product bet.
                boundary: Only the startup-method parts are directly relevant.
                ---
                """));

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals(List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid description."),
                PublicField.of("use", "Explains how to test demand before scaling a product bet."),
                PublicField.of("boundary", "Only the startup-method parts are directly relevant.")),
                result.fields());
        assertEquals("""
                authors:
                  - "Eric Ries"
                  - "Cindy Alvarez"
                publication: "Crown Business"
                publicationDate: "2011-09-13"
                start: "2026-01-10"
                end: "2026-01-20"
                readingStatus: "finished"
                """, result.structuredData());
    }

    private static List<String> blockedFields(AdmittedPublication result) {
        return result.diagnostics().stream().map(Diagnostic::field).toList();
    }

    private static BookPublicationKindFixture fixtureNamed(String name) {
        return BookPublicationKindFixtures.all().stream()
                .filter(fixture -> fixture.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
