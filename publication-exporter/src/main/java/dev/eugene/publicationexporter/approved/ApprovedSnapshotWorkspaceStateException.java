package dev.eugene.publicationexporter.approved;

public class ApprovedSnapshotWorkspaceStateException extends IllegalStateException {

    protected ApprovedSnapshotWorkspaceStateException(String message) {
        super(message);
    }

    protected ApprovedSnapshotWorkspaceStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
