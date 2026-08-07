package dev.eugene.publicationexporter.approved;

import java.nio.file.Path;

public final class ApprovedSnapshotIntegrityException extends ApprovedSnapshotWorkspaceStateException {

    public ApprovedSnapshotIntegrityException(Path snapshotDirectory, String detail) {
        super("Approved snapshot integrity validation failed at " + snapshotDirectory + ": " + detail);
    }

    public ApprovedSnapshotIntegrityException(Path snapshotDirectory, String detail, Throwable cause) {
        super("Approved snapshot integrity validation failed at " + snapshotDirectory + ": " + detail, cause);
    }
}
