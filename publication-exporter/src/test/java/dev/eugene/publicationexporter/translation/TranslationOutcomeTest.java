package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranslationOutcomeTest {

    @Test
    void successMapsEnglishTranslationToItsConsumer() {
        TranslationOutcome result = TranslationOutcome.success("Hello", "Hi there", "A description.");

        String observed = result.resolve(
                translation -> translation.body() + "|" + translation.title() + "|" + translation.description(),
                failure -> "failed:" + failure.diagnosticField() + ":" + failure.reason());

        assertEquals("Hello|Hi there|A description.", observed);
    }

    @Test
    void defaultFailureMapsDiagnosticToItsConsumer() {
        TranslationOutcome result = TranslationOutcome.failure("boom");

        String observed = result.resolve(
                translation -> "translated:" + translation.body(),
                failure -> failure.diagnosticField() + ":" + failure.reason());

        assertEquals("candidate:boom", observed);
    }

    @Test
    void explicitFailureMapsItsDiagnosticToItsConsumer() {
        TranslationOutcome result = TranslationOutcome.failure("translation-engine", "unsupported agent");

        String observed = result.resolve(
                translation -> "translated:" + translation.title(),
                failure -> failure.diagnosticField() + ":" + failure.reason());

        assertEquals("translation-engine:unsupported agent", observed);
    }

    @Test
    void staleMapsToTheStaleFailure() {
        TranslationOutcome result = TranslationOutcome.stale();

        String observed = result.resolve(
                translation -> "translated:" + translation.description(),
                failure -> failure.reason());

        assertEquals("Translation result is stale.", observed);
    }

    @Test
    void successRejectsNullTitle() {
        assertThrows(NullPointerException.class, () -> TranslationOutcome.success("Hello", null, "d"));
    }

    @Test
    void successRejectsNullBody() {
        assertThrows(NullPointerException.class, () -> TranslationOutcome.success(null, "t", "d"));
    }

    @Test
    void successRejectsNullDescription() {
        assertThrows(NullPointerException.class, () -> TranslationOutcome.success("Hello", "t", null));
    }

    @Test
    void failureRejectsNullReason() {
        assertThrows(NullPointerException.class, () -> TranslationOutcome.failure(null));
    }
}
