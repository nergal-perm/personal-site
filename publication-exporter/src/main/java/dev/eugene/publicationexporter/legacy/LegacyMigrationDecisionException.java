package dev.eugene.publicationexporter.legacy;

public final class LegacyMigrationDecisionException extends RuntimeException {

    public LegacyMigrationDecisionException(String message) {
        super(message);
    }

    public LegacyMigrationDecisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
