package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullTranslationWorkerTest {

    @Test
    void configuredSuccessIsReturnedForAnyRequestedTranslation() {
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationResult.success("EN body", "EN title", "EN description."));

        TranslationResult result = worker.translate("RU body", "RU title", "RU description.");

        assertTrue(result.succeeded());
        assertEquals("EN body", result.enBody());
        assertEquals("EN title", result.enTitle());
        assertEquals("EN description.", result.enDescription());
    }

    @Test
    void configuredFailureIsReturnedForAnyRequestedTranslation() {
        NullTranslationWorker worker = new NullTranslationWorker(TranslationResult.failure("boom"));

        TranslationResult result = worker.translate("RU body", "RU title", "RU description.");

        assertFalse(result.succeeded());
        assertEquals("boom", result.failureReason());
    }

    @Test
    void everyRequestedTranslationIsTracked() {
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationResult.success("EN", "EN title", "EN description."));

        worker.translate("first body", "first title", "first description");
        worker.translate("second body", "second title", "second description");

        assertEquals(java.util.List.of(
                new NullTranslationWorker.RequestedTranslation("first body", "first title", "first description"),
                new NullTranslationWorker.RequestedTranslation("second body", "second title", "second description")),
                worker.requested());
    }

    @Test
    void interfaceFactoriesProduceTheSameBehaviour() {
        TranslationResult result = TranslationWorker.createNull("EN", "EN title", "EN description")
                .translate("RU", "RU title", "RU description");

        assertTrue(result.succeeded());
        assertEquals("EN title", result.enTitle());
        assertEquals("EN description", result.enDescription());
        assertFalse(TranslationWorker.createNullFailing("boom")
                .translate("RU", "RU title", "RU description").succeeded());
    }
}
