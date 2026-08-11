package dev.eugene.publicationexporter.translation;

import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranslationOutcomeTest {

    @Test
    void successMapsEnglishTranslationToItsConsumer() {
        TranslationOutcome result = TranslationOutcome.success("Hello", List.of(
                PublicField.of("title", "Hi there"), PublicField.of("description", "A description.")));

        String observed = result.resolve(
                translation -> translation.body() + "|" + translation.fields().get(0).value()
                        + "|" + translation.fields().get(1).value(),
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
                translation -> "translated:" + translation.fields().get(0).value(),
                failure -> failure.diagnosticField() + ":" + failure.reason());

        assertEquals("translation-engine:unsupported agent", observed);
    }

    @Test
    void staleMapsToTheStaleFailure() {
        TranslationOutcome result = TranslationOutcome.stale();

        String observed = result.resolve(
                translation -> "translated:" + translation.fields().get(1).value(),
                failure -> failure.reason());

        assertEquals("Translation result is stale.", observed);
    }

    @Test
    void successRejectsNullFields() {
        assertThrows(NullPointerException.class, () -> TranslationOutcome.success("Hello", null));
    }

    @Test
    void successRejectsNullBody() {
        assertThrows(NullPointerException.class, () -> TranslationOutcome.success(null, List.of(
                PublicField.of("title", "t"), PublicField.of("description", "d"))));
    }

    @Test
    void successRejectsNullDescription() {
        assertThrows(NullPointerException.class, () -> TranslationOutcome.success("Hello", List.of(
                PublicField.of("title", "t"), null)));
    }

    @Test
    void failureRejectsNullReason() {
        assertThrows(NullPointerException.class, () -> TranslationOutcome.failure(null));
    }
}
