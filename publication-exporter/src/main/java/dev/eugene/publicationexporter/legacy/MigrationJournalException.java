package dev.eugene.publicationexporter.legacy;

public final class MigrationJournalException extends IllegalStateException {
    public MigrationJournalException(String message, Throwable cause) { super(message, cause); }
    public MigrationJournalException(String message) { super(message); }
}
