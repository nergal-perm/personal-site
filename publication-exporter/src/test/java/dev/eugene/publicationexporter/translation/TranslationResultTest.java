package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationResultTest {

    @Test
    void successExposesEnBody() {
        TranslationResult result = TranslationResult.success("Hello");

        assertTrue(result.succeeded());
        assertEquals("Hello", result.enBody());
    }

    @Test
    void failureExposesReason() {
        TranslationResult result = TranslationResult.failure("boom");

        assertFalse(result.succeeded());
        assertEquals("boom", result.failureReason());
    }

    @Test
    void successRejectsNullBody() {
        assertThrows(NullPointerException.class, () -> TranslationResult.success(null));
    }

    @Test
    void failureRejectsNullReason() {
        assertThrows(NullPointerException.class, () -> TranslationResult.failure(null));
    }
}
