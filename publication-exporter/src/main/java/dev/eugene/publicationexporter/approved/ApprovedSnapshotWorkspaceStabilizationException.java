package dev.eugene.publicationexporter.approved;

import java.nio.file.Path;

public final class ApprovedSnapshotWorkspaceStabilizationException extends ApprovedSnapshotWorkspaceStateException {

    public ApprovedSnapshotWorkspaceStabilizationException(Path approvedDirectory, int attempts) {
        super("Approved snapshot read could not stabilize after " + attempts
                + " attempts: directory generation kept changing at " + approvedDirectory + ".");
    }
}
