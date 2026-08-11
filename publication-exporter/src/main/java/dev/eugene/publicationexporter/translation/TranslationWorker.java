package dev.eugene.publicationexporter.translation;

import dev.eugene.publicationexporter.reference.PublicField;

import java.util.List;

public interface TranslationWorker {

    TranslationOutcome translate(TranslationJob job, String ruBody, List<PublicField> ruFields);

    static TranslationWorker createNull(String enBody, List<PublicField> enFields) {
        return new NullTranslationWorker(TranslationOutcome.success(enBody, enFields));
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
