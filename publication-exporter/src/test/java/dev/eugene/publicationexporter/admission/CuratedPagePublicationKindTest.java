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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuratedPagePublicationKindTest {

    private final CuratedPagePublicationKind admission = new CuratedPagePublicationKind();

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.eugene.publicationexporter.admission.CuratedPagePublicationKindFixtures#all")
    void admitsOrBlocksPerSharedFixture(CuratedPagePublicationKindFixture fixture) {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(fixture.noteSource()));

        assertEquals(fixture.expectedAccepted(), result.accepted(), fixture.name());
    }

    @Test
    void validAboutPageOwnsEditorialIdentityAndTranslatedFields() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(validNote("")));

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals(PublicationIdentity.of("editorial", "curated_page", "about"), result.identity());
        assertEquals("source-about", result.sourceId());
        assertEquals(List.of(
                PublicField.of("title", "About"),
                PublicField.of("summary", "Кратко."),
                PublicField.of("eyebrow", "Бровь."),
                PublicField.of("lead", "Лид."),
                PublicField.of("principles[0].title", "Первый"),
                PublicField.of("principles[0].text", "Принцип."),
                PublicField.of("colophon", "Колофон.")), result.fields());
        assertEquals("{\"searchable\":false,\"type\":\"about\"}", result.structuredData());
    }

    @Test
    void publicSearchableTrueIsPreservedAsStructuredData() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(validNote("publicSearchable: true\n")));

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals("{\"searchable\":true,\"type\":\"about\"}", result.structuredData());
    }

    @Test
    void publicIdMustMatchEditorialPage() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(validNote("different", "about", "")));

        assertTrue(blockedFields(result).contains("publicId"));
    }

    @Test
    void knownUnsupportedPageKeyNamesTheSupportedPage() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(validNote("about", "home", "")));

        assertTrue(blockedFields(result).contains("editorialPage"));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.message().contains("only 'about' is supported")));
    }

    @Test
    void unknownPageKeyIsRejectedAsUnsupportedIdentity() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(validNote("about", "nonsense", "")));

        assertTrue(blockedFields(result).contains("editorialPage"));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.message().contains("must be one of:")));
    }

    @Test
    void malformedAboutBodyIsTranslatedIntoBodyDiagnostic() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(validNote("", """
                ## Кратко

                Кратко.

                ## Eyebrow

                Бровь.

                ## Лид

                Лид.

                ## Принципы

                ### Первый

                Принцип.
                """)));

        assertEquals(List.of("body"), blockedFields(result));
        assertTrue(result.diagnostics().get(0).message().contains("Колофон"));
    }

    @Test
    void curatedPageHasNoSharedRoutePrefix() {
        assertNull(admission.routePrefix());
        assertEquals("editorial", admission.collection());
        assertEquals("curated_page", admission.contentType());
    }

    private static String validNote(String additionalFrontmatter) {
        return validNote("about", "about", additionalFrontmatter, """
                ## Кратко

                Кратко.

                ## Eyebrow

                Бровь.

                ## Лид

                Лид.

                ## Принципы

                ### Первый

                Принцип.

                ## Колофон

                Колофон.
                """);
    }

    private static String validNote(String additionalFrontmatter, String body) {
        return validNote("about", "about", additionalFrontmatter, body);
    }

    private static String validNote(String publicId, String editorialPage, String additionalFrontmatter) {
        return validNote(publicId, editorialPage, additionalFrontmatter, """
                ## Кратко

                Кратко.

                ## Eyebrow

                Бровь.

                ## Лид

                Лид.

                ## Принципы

                ### Первый

                Принцип.

                ## Колофон

                Колофон.
                """);
    }

    private static String validNote(
            String publicId, String editorialPage, String additionalFrontmatter, String body) {
        return """
                ---
                publish: true
                publicCollection: editorial
                publicContentType: curated_page
                publicId: %s
                editorialPage: %s
                id: source-about
                title: About
                """.formatted(publicId, editorialPage) + additionalFrontmatter + "---\n" + body;
    }

    private static List<String> blockedFields(AdmittedPublication result) {
        return result.diagnostics().stream().map(Diagnostic::field).toList();
    }
}
