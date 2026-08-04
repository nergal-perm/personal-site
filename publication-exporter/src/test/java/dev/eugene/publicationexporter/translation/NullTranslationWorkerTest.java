package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullTranslationWorkerTest {

    @Test
    void configuredSuccessIsReturnedForAnyRequestedBody() {
        NullTranslationWorker worker = new NullTranslationWorker(TranslationResult.success("EN body"));

        TranslationResult result = worker.translate("RU body");

        assertTrue(result.succeeded());
        assertEquals("EN body", result.enBody());
    }

    @Test
    void configuredFailureIsReturnedForAnyRequestedBody() {
        NullTranslationWorker worker = new NullTranslationWorker(TranslationResult.failure("boom"));

        TranslationResult result = worker.translate("RU body");

        assertFalse(result.succeeded());
        assertEquals("boom", result.failureReason());
    }

    @Test
    void everyRequestedBodyIsTracked() {
        NullTranslationWorker worker = new NullTranslationWorker(TranslationResult.success("EN"));

        worker.translate("first");
        worker.translate("second");

        assertEquals(java.util.List.of("first", "second"), worker.requestedBodies());
    }

    @Test
    void interfaceFactoriesProduceTheSameBehaviour() {
        assertTrue(TranslationWorker.createNull("EN").translate("RU").succeeded());
        assertFalse(TranslationWorker.createNullFailing("boom").translate("RU").succeeded());
    }
}
