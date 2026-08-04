package dev.eugene.publicationexporter.translation;

import java.util.Objects;

public final class TranslationResult {

    private final String enBody;
    private final String failureReason;

    private TranslationResult(String enBody, String failureReason) {
        this.enBody = enBody;
        this.failureReason = failureReason;
    }

    public static TranslationResult success(String enBody) {
        return new TranslationResult(Objects.requireNonNull(enBody, "enBody"), null);
    }

    public static TranslationResult failure(String reason) {
        return new TranslationResult(null, Objects.requireNonNull(reason, "reason"));
    }

    public boolean succeeded() {
        return enBody != null;
    }

    /** Only meaningful when {@link #succeeded()} is {@code true}. */
    public String enBody() {
        return enBody;
    }

    /** Only meaningful when {@link #succeeded()} is {@code false}. */
    public String failureReason() {
        return failureReason;
    }

    @Override
    public String toString() {
        return "TranslationResult[enBody=" + enBody + ", failureReason=" + failureReason + "]";
    }
}
