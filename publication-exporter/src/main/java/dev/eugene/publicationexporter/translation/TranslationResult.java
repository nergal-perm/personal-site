package dev.eugene.publicationexporter.translation;

import java.util.Objects;

public final class TranslationResult {

    private final String enBody;
    private final String enTitle;
    private final String enDescription;
    private final String failureDiagnosticField;
    private final String failureReason;

    private TranslationResult(
            String enBody, String enTitle, String enDescription,
            String failureDiagnosticField, String failureReason) {
        this.enBody = enBody;
        this.enTitle = enTitle;
        this.enDescription = enDescription;
        this.failureDiagnosticField = failureDiagnosticField;
        this.failureReason = failureReason;
    }

    public static TranslationResult success(String enBody, String enTitle, String enDescription) {
        return new TranslationResult(
                Objects.requireNonNull(enBody, "enBody"),
                Objects.requireNonNull(enTitle, "enTitle"),
                Objects.requireNonNull(enDescription, "enDescription"),
                null,
                null);
    }

    public static TranslationResult failure(String reason) {
        return failure("candidate", reason);
    }

    public static TranslationResult failure(String diagnosticField, String reason) {
        return new TranslationResult(null, null, null,
                Objects.requireNonNull(diagnosticField, "diagnosticField"),
                Objects.requireNonNull(reason, "reason"));
    }

    public static TranslationResult stale() {
        return failure("Translation result is stale.");
    }

    public boolean succeeded() {
        return enBody != null;
    }

    /** Only meaningful when {@link #succeeded()} is {@code true}. */
    public String enBody() {
        return enBody;
    }

    /** Only meaningful when {@link #succeeded()} is {@code true}. */
    public String enTitle() {
        return enTitle;
    }

    /** Only meaningful when {@link #succeeded()} is {@code true}. */
    public String enDescription() {
        return enDescription;
    }

    /** Only meaningful when {@link #succeeded()} is {@code false}. */
    public String failureReason() {
        return failureReason;
    }

    /** Only meaningful when {@link #succeeded()} is {@code false}. */
    public String failureDiagnosticField() {
        return failureDiagnosticField;
    }

    @Override
    public String toString() {
        return "TranslationResult[enBody=" + enBody + ", enTitle=" + enTitle
                + ", enDescription=" + enDescription + ", failureDiagnosticField=" + failureDiagnosticField
                + ", failureReason=" + failureReason + "]";
    }
}
