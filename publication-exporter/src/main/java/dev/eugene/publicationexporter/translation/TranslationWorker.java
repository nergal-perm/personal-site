package dev.eugene.publicationexporter.translation;

public interface TranslationWorker {

    TranslationOutcome translate(TranslationJob job, String ruBody, String ruTitle, String ruDescription);

    static TranslationWorker createNull(String enBody, String enTitle, String enDescription) {
        return new NullTranslationWorker(TranslationOutcome.success(enBody, enTitle, enDescription));
    }

    static TranslationWorker createNullFailing(String reason) {
        return new NullTranslationWorker(TranslationOutcome.failure(reason));
    }

    static TranslationWorker createNullFailing(String diagnosticField, String reason) {
        return new NullTranslationWorker(TranslationOutcome.failure(diagnosticField, reason));
    }

    static TranslationWorker createNullStale() {
        return new NullTranslationWorker(TranslationOutcome.stale());
    }
}
