package dev.eugene.publicationexporter.prepare;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnglishCandidateValidatorTest {

    @Test
    void acceptsStructurallyCompleteCandidate() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Смотрите https://example.com/x для деталей.",
                "See https://example.com/x for details.", "Title", "Description");

        assertTrue(result.valid());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void acceptsRetainedExternalUrlContainingRuRouteInAllFields() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Смотрите https://example.com/ru/docs для деталей.",
                "See https://example.com/ru/docs for details.",
                "Read https://example.com/ru/docs",
                "Details at https://example.com/ru/docs");

        assertTrue(result.valid());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void rejectsBlankBody() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "   ", "Title", "Description");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("body")));
    }

    @Test
    void rejectsBlankTitle() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "Body", "  ", "Description");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("title")));
    }

    @Test
    void rejectsBlankDescription() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "Body", "Title", "  ");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("description")));
    }

    @Test
    void rejectsInternalRuRoute() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Смотрите [другую статью](/ru/blog/other) для деталей.",
                "See [another essay](/ru/blog/other) for details.", "Title", "Description");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("/ru/")));
    }

    @Test
    void rejectsReferenceStyleInternalRuRoute() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "See [another essay][ru].\n\n[ru]: /ru/blog/other", "Title", "Description");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("/ru/")));
    }

    @Test
    void rejectsHtmlInternalRuRoute() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "See <a href=\"/ru/blog/other\">another essay</a>.", "Title", "Description");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("/ru/")));
    }

    @Test
    void rejectsInternalRuRouteInTitle() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "Body", "Read /ru/blog/other", "Description");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("/ru/")));
    }

    @Test
    void rejectsInternalRuRouteInDescription() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Текст", "Body", "Title", "Read /ru/blog/other");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("/ru/")));
    }

    @Test
    void rejectsDroppedExternalUrl() {
        EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
                "Смотрите https://example.com/x для деталей.",
                "See the details.", "Title", "Description");

        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("https://example.com/x")));
    }

    @Test
    void resultSupportsValueEqualityAndHashing() {
        EnglishCandidateValidator.Result first = EnglishCandidateValidator.validate(
                "Текст", "See https://example.com/x", "Title", "Description");
        EnglishCandidateValidator.Result second = EnglishCandidateValidator.validate(
                "Текст", "See https://example.com/x", "Title", "Description");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}
