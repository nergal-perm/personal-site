package dev.eugene.publicationexporter.translation;

public interface TranslationWorker {

    TranslationResult translate(TranslationJob job, String ruBody, String ruTitle, String ruDescription);

    static TranslationWorker createNull(String enBody, String enTitle, String enDescription) {
        return new NullTranslationWorker(TranslationResult.success(enBody, enTitle, enDescription));
    }

    static TranslationWorker createNullFailing(String reason) {
        return new NullTranslationWorker(TranslationResult.failure(reason));
    }

    static TranslationWorker createNullFailing(String diagnosticField, String reason) {
        return new NullTranslationWorker(TranslationResult.failure(diagnosticField, reason));
    }

    static TranslationWorker createNullStale() {
        return new NullTranslationWorker(TranslationResult.stale());
    }
}
