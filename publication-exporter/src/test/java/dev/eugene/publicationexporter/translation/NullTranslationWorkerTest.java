package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NullTranslationWorkerTest {

    @Test
    void configuredSuccessIsReturnedForAnyRequestedTranslation() {
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN body", "EN title", "EN description."));
        TranslationJob job = TranslationJob.forSource("RU body", "RU title", "RU description.");

        TranslationOutcome result = worker.translate(job, "RU body", "RU title", "RU description.");

        assertEquals("EN body", TranslationResults.translated(result).body());
        assertEquals("EN title", TranslationResults.translated(result).title());
        assertEquals("EN description.", TranslationResults.translated(result).description());
    }

    @Test
    void configuredFailureIsReturnedForAnyRequestedTranslation() {
        NullTranslationWorker worker = new NullTranslationWorker(TranslationOutcome.failure("boom"));
        TranslationJob job = TranslationJob.forSource("RU body", "RU title", "RU description.");

        TranslationOutcome result = worker.translate(job, "RU body", "RU title", "RU description.");

        assertEquals("boom", TranslationResults.failed(result).reason());
    }

    @Test
    void everyRequestedTranslationIsTracked() {
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", "EN title", "EN description."));
        TranslationJob firstJob = TranslationJob.forSource("first body", "first title", "first description");
        TranslationJob secondJob = TranslationJob.forSource("second body", "second title", "second description");

        worker.translate(firstJob, "first body", "first title", "first description");
        worker.translate(secondJob, "second body", "second title", "second description");

        assertEquals(java.util.List.of(
                new NullTranslationWorker.RequestedTranslation("first body", "first title", "first description"),
                new NullTranslationWorker.RequestedTranslation("second body", "second title", "second description")),
                worker.requested());
    }

    @Test
    void interfaceFactoriesProduceTheSameBehaviour() {
        TranslationOutcome result = TranslationWorker.createNull("EN", "EN title", "EN description")
                .translate(TranslationJob.forSource("RU", "RU title", "RU description"),
                        "RU", "RU title", "RU description");

        assertEquals("EN title", TranslationResults.translated(result).title());
        assertEquals("EN description", TranslationResults.translated(result).description());
        assertEquals("boom", TranslationResults.failed(TranslationWorker.createNullFailing("boom")
                .translate(TranslationJob.forSource("RU", "RU title", "RU description"),
                        "RU", "RU title", "RU description")).reason());
    }

    @Test
    void staleFactoryReturnsAStaleTranslationFailure() {
        TranslationOutcome result = TranslationWorker.createNullStale()
                .translate(TranslationJob.forSource("RU", "RU title", "RU description"),
                        "RU", "RU title", "RU description");

        assertEquals("Translation result is stale.", TranslationResults.failed(result).reason());
    }
}
