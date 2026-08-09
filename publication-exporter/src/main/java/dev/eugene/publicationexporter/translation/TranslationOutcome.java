package dev.eugene.publicationexporter.translation;

import java.util.Objects;
import java.util.function.Function;

public sealed interface TranslationOutcome permits SuccessfulTranslation, FailedTranslation {

    static TranslationOutcome success(String enBody, String enTitle, String enDescription) {
        return new SuccessfulTranslation(EnglishTranslation.of(enBody, enTitle, enDescription));
    }

    static TranslationOutcome failure(String reason) {
        return failure("candidate", reason);
    }

    static TranslationOutcome failure(String diagnosticField, String reason) {
        return new FailedTranslation(TranslationFailure.of(diagnosticField, reason));
    }

    static TranslationOutcome stale() {
        return failure("Translation result is stale.");
    }

    <T> T resolve(
            Function<EnglishTranslation, T> onSuccess,
            Function<TranslationFailure, T> onFailure);
}

final class SuccessfulTranslation implements TranslationOutcome {

    private final EnglishTranslation translation;

    SuccessfulTranslation(EnglishTranslation translation) {
        this.translation = Objects.requireNonNull(translation, "translation");
    }

    @Override
    public <T> T resolve(
            Function<EnglishTranslation, T> onSuccess,
            Function<TranslationFailure, T> onFailure) {
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onFailure, "onFailure");
        return onSuccess.apply(translation);
    }
}

final class FailedTranslation implements TranslationOutcome {

    private final TranslationFailure failure;

    FailedTranslation(TranslationFailure failure) {
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    @Override
    public <T> T resolve(
            Function<EnglishTranslation, T> onSuccess,
            Function<TranslationFailure, T> onFailure) {
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onFailure, "onFailure");
        return onFailure.apply(failure);
    }
}
