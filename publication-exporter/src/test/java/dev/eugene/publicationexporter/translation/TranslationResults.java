package dev.eugene.publicationexporter.translation;

import java.util.function.Function;

final class TranslationResults {

    private TranslationResults() {
    }

    static EnglishTranslation translated(TranslationOutcome result) {
        return result.resolve(
                Function.identity(),
                failure -> {
                    throw new AssertionError("Expected a translated result but got: " + failure.reason());
                });
    }

    static TranslationFailure failed(TranslationOutcome result) {
        return result.resolve(
                translation -> {
                    throw new AssertionError("Expected a failed result but got: " + translation.body());
                },
                Function.identity());
    }
}
