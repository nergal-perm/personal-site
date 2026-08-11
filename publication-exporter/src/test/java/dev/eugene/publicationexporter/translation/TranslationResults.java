package dev.eugene.publicationexporter.translation;

import dev.eugene.publicationexporter.reference.PublicField;

import java.util.List;
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

    static List<PublicField> fields(String title, String description) {
        return List.of(PublicField.of("title", title), PublicField.of("description", description));
    }
}
