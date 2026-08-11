package dev.eugene.publicationexporter.translation;

import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NullTranslationWorkerTest {

    @Test
    void configuredSuccessIsReturnedForAnyRequestedTranslation() {
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN body", List.of(
                        PublicField.of("title", "EN title"), PublicField.of("description", "EN description."))));
        TranslationJob job = TranslationJob.forSource("RU body", List.of(
                PublicField.of("title", "RU title"), PublicField.of("description", "RU description.")));

        TranslationOutcome result = worker.translate(job, "RU body", List.of(
                PublicField.of("title", "RU title"), PublicField.of("description", "RU description.")));

        assertEquals("EN body", TranslationResults.translated(result).body());
        assertEquals("EN title", TranslationResults.translated(result).fields().get(0).value());
        assertEquals("EN description.", TranslationResults.translated(result).fields().get(1).value());
    }

    @Test
    void configuredFailureIsReturnedForAnyRequestedTranslation() {
        NullTranslationWorker worker = new NullTranslationWorker(TranslationOutcome.failure("boom"));
        TranslationJob job = TranslationJob.forSource("RU body", List.of(
                PublicField.of("title", "RU title"), PublicField.of("description", "RU description.")));

        TranslationOutcome result = worker.translate(job, "RU body", List.of(
                PublicField.of("title", "RU title"), PublicField.of("description", "RU description.")));

        assertEquals("boom", TranslationResults.failed(result).reason());
    }

    @Test
    void everyRequestedTranslationIsTracked() {
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", List.of(
                        PublicField.of("title", "EN title"), PublicField.of("description", "EN description."))));
        TranslationJob firstJob = TranslationJob.forSource("first body", List.of(
                PublicField.of("title", "first title"), PublicField.of("description", "first description")));
        TranslationJob secondJob = TranslationJob.forSource("second body", List.of(
                PublicField.of("title", "second title"), PublicField.of("description", "second description")));

        List<PublicField> firstFields = List.of(
                PublicField.of("title", "first title"), PublicField.of("description", "first description"));
        List<PublicField> secondFields = List.of(
                PublicField.of("title", "second title"), PublicField.of("description", "second description"));
        worker.translate(firstJob, "first body", firstFields);
        worker.translate(secondJob, "second body", secondFields);

        assertEquals(java.util.List.of(
                new NullTranslationWorker.RequestedTranslation("first body", firstFields),
                new NullTranslationWorker.RequestedTranslation("second body", secondFields)),
                worker.requested());
    }

    @Test
    void interfaceFactoriesProduceTheSameBehaviour() {
        List<PublicField> englishFields = List.of(
                PublicField.of("title", "EN title"), PublicField.of("description", "EN description"));
        List<PublicField> russianFields = List.of(
                PublicField.of("title", "RU title"), PublicField.of("description", "RU description"));
        TranslationOutcome result = TranslationWorker.createNull("EN", englishFields)
                .translate(TranslationJob.forSource("RU", russianFields), "RU", russianFields);

        assertEquals("EN title", TranslationResults.translated(result).fields().get(0).value());
        assertEquals("EN description", TranslationResults.translated(result).fields().get(1).value());
        assertEquals("boom", TranslationResults.failed(TranslationWorker.createNullFailing("boom")
                .translate(TranslationJob.forSource("RU", russianFields), "RU", russianFields)).reason());
    }

    @Test
    void staleFactoryReturnsAStaleTranslationFailure() {
        TranslationOutcome result = TranslationWorker.createNullStale()
                .translate(TranslationJob.forSource("RU", russianFields()), "RU", russianFields());

        assertEquals("Translation result is stale.", TranslationResults.failed(result).reason());
    }

    private static List<PublicField> russianFields() {
        return List.of(PublicField.of("title", "RU title"), PublicField.of("description", "RU description"));
    }
}
