package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationResultTest {

    @Test
    void successExposesEnBodyTitleAndDescription() {
        TranslationResult result = TranslationResult.success("Hello", "Hi there", "A description.");

        assertTrue(result.succeeded());
        assertEquals("Hello", result.enBody());
        assertEquals("Hi there", result.enTitle());
        assertEquals("A description.", result.enDescription());
    }

    @Test
    void failureExposesReason() {
        TranslationResult result = TranslationResult.failure("boom");

        assertFalse(result.succeeded());
        assertEquals("boom", result.failureReason());
    }

    @Test
    void successRejectsNullTitle() {
        assertThrows(NullPointerException.class, () -> TranslationResult.success("Hello", null, "d"));
    }

    @Test
    void successRejectsNullBody() {
        assertThrows(NullPointerException.class, () -> TranslationResult.success(null, "t", "d"));
    }

    @Test
    void successRejectsNullDescription() {
        assertThrows(NullPointerException.class, () -> TranslationResult.success("Hello", "t", null));
    }

    @Test
    void failureRejectsNullReason() {
        assertThrows(NullPointerException.class, () -> TranslationResult.failure(null));
    }
}
