package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnglishCandidateValidatorTest {

    @Test
    void acceptsStructurallyCompleteCandidate() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Смотрите https://example.com/x для деталей.",
                "See https://example.com/x for details.", fields("Title", "Description"));

        assertTrue(result.valid());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void acceptsRetainedExternalUrlContainingRuRouteInAllFields() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Смотрите https://example.com/ru/docs для деталей.",
                "See https://example.com/ru/docs for details.",
                fields("Read https://example.com/ru/docs", "Details at https://example.com/ru/docs"));

        assertTrue(result.valid());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void rejectsBlankBody() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "   ", fields("Title", "Description"));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("body")));
    }

    @Test
    void blankSourceAndTranslatedBodyIsNotAWorkerFailure() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "", List.of(PublicField.of("title", "Заголовок")),
                "", List.of(PublicField.of("title", "Title")));
        assertTrue(result.valid(), result.diagnostics().toString());
    }

    @Test
    void blankTranslatedBodyIsStillAWorkerFailureWhenSourceHadContent() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Русский текст.", List.of(PublicField.of("title", "Заголовок")),
                "", List.of(PublicField.of("title", "Title")));
        assertTrue(result.diagnostics().contains("Translation worker produced a blank body."));
    }

    @Test
    void rejectsBlankTitle() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "Body", fields("  ", "Description"));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("title")));
    }

    @Test
    void rejectsBlankDescription() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "Body", fields("Title", "  "));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("description")));
    }

    @Test
    void rejectsInternalRuRoute() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Смотрите [другую статью](/ru/blog/other) для деталей.",
                "See [another essay](/ru/blog/other) for details.", fields("Title", "Description"));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("/ru/")));
    }

    @Test
    void rejectsReferenceStyleInternalRuRoute() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "See [another essay][ru].\n\n[ru]: /ru/blog/other", fields("Title", "Description"));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("/ru/")));
    }

    @Test
    void rejectsHtmlInternalRuRoute() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "See <a href=\"/ru/blog/other\">another essay</a>.", fields("Title", "Description"));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("/ru/")));
    }

    @Test
    void rejectsInternalRuRouteInTitle() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "Body", fields("Read /ru/blog/other", "Description"));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("/ru/")));
    }

    @Test
    void rejectsInternalRuRouteInDescription() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "Body", fields("Title", "Read /ru/blog/other"));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("/ru/")));
    }

    @Test
    void rejectsDroppedExternalUrl() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Смотрите https://example.com/x для деталей.",
                "See the details.", fields("Title", "Description"));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("https://example.com/x")));
    }

    @Test
    void rejectsDroppedExternalUrlFromTranslatedBookField() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст",
                bookFields("Заголовок", "Описание", "Смотрите https://example.com/use", "Граница"),
                "Body",
                bookFields("Title", "Description", "See the use note.", "Boundary"));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains("https://example.com/use")));
    }

    @Test
    void droppedAssetReferenceIsReportedAsInvalid() {
        String ruBody = "See ![cover](/assets/vault/abc123.png).";
        String enBody = "See the cover image.";

        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                ruBody, enBody, fields("Title", "Description."));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains("dropped asset reference")));
    }

    @Test
    void droppedAssetReferenceFromTranslatedBookFieldIsReportedAsInvalid() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст",
                bookFields("Заголовок", "Описание", "См. ![cover](/assets/vault/abc123.png).", "Граница"),
                "Body",
                bookFields("Title", "Description", "See the cover note.", "Boundary"));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains("/assets/vault/abc123.png")));
    }

    @Test
    void replacedAssetReferenceHostIsReportedAsDropped() {
        String ruBody = "See ![cover](/assets/vault/abc123.png).";
        String enBody = "See ![cover](https://cdn.example/assets/vault/abc123.png).";

        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                ruBody, enBody, fields("Title", "Description."));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(
                diagnostic -> diagnostic.contains("/assets/vault/abc123.png")));
    }

    @Test
    void replacedAssetReferenceHostWithNonAsciiDomainIsReportedAsDropped() {
        String ruBody = "See ![cover](/assets/vault/abc123.png).";
        String enBody = "See ![cover](https://cdn.пример/assets/vault/abc123.png).";

        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                ruBody, enBody, fields("Title", "Description."));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(
                diagnostic -> diagnostic.contains("/assets/vault/abc123.png")));
    }

    @Test
    void protocolRelativeAssetReferenceIsReportedAsDropped() {
        String ruBody = "See ![cover](/assets/vault/abc123.png).";
        String enBody = "See ![cover](//assets/vault/abc123.png).";

        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                ruBody, enBody, fields("Title", "Description."));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(
                diagnostic -> diagnostic.contains("/assets/vault/abc123.png")));
    }

    @Test
    void queryStringEmbeddedAssetReferenceIsReportedAsDropped() {
        String ruBody = "See ![cover](/assets/vault/abc123.png).";
        String enBody = "See https://cdn.example/?next=/assets/vault/abc123.png";

        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                ruBody, enBody, fields("Title", "Description."));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(
                diagnostic -> diagnostic.contains("/assets/vault/abc123.png")));
    }

    @Test
    void fragmentEmbeddedAssetReferenceIsReportedAsDropped() {
        String ruBody = "See ![cover](/assets/vault/abc123.png).";
        String enBody = "See https://cdn.example/#/assets/vault/abc123.png";

        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                ruBody, enBody, fields("Title", "Description."));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(
                diagnostic -> diagnostic.contains("/assets/vault/abc123.png")));
    }

    @Test
    void preservedAssetReferenceIsValid() {
        String ruBody = "See ![cover](/assets/vault/abc123.png).";
        String enBody = "See ![cover](/assets/vault/abc123.png).";

        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                ruBody, enBody, fields("Title", "Description."));

        assertTrue(result.valid());
    }

    @Test
    void rejectsMissingTranslatedBookField() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст",
                bookFields("Заголовок", "Описание", "Использование", "Граница"),
                "Body",
                fields("Title", "Description"));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains("translated field structure")));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains("use")));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains("boundary")));
    }

    @Test
    void rejectsExtraTranslatedBookField() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст",
                fields("Заголовок", "Описание"),
                "Body",
                bookFields("Title", "Description", "Use", "Boundary"));

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains("translated field structure")));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains("use")));
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains("boundary")));
    }

    @Test
    void resultSupportsValueEqualityAndHashing() {
        EnglishCandidateValidator.Result first = EnglishCandidateValidator.validate(
                "Текст", "See https://example.com/x", fields("Title", "Description"));
        EnglishCandidateValidator.Result second = EnglishCandidateValidator.validate(
                "Текст", "See https://example.com/x", fields("Title", "Description"));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    private static List<PublicField> fields(String title, String description) {
        return List.of(PublicField.of("title", title), PublicField.of("description", description));
    }

    private static List<PublicField> bookFields(String title, String description, String use, String boundary) {
        return List.of(
                PublicField.of("title", title),
                PublicField.of("description", description),
                PublicField.of("use", use),
                PublicField.of("boundary", boundary));
    }
}
