package dev.eugene.publicationexporter.legacy;

public final class SemanticOperationInProgressException extends RuntimeException {

    public SemanticOperationInProgressException() {
        super("Another semantic operation is already in progress.");
    }
}
