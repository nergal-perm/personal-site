package dev.eugene.publicationexporter.translation;

import java.util.Objects;

public final class TranslationFailure {

    private final String diagnosticField;
    private final String reason;

    private TranslationFailure(String diagnosticField, String reason) {
        this.diagnosticField = Objects.requireNonNull(diagnosticField, "diagnosticField");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public static TranslationFailure of(String diagnosticField, String reason) {
        return new TranslationFailure(diagnosticField, reason);
    }

    public String diagnosticField() {
        return diagnosticField;
    }

    public String reason() {
        return reason;
    }
}
