package dev.eugene.publicationexporter.translation;

public interface TranslationWorker {

    TranslationResult translate(String ruBody);

    static TranslationWorker createNull(String enBody) {
        return new NullTranslationWorker(TranslationResult.success(enBody));
    }

    static TranslationWorker createNullFailing(String reason) {
        return new NullTranslationWorker(TranslationResult.failure(reason));
    }
}
