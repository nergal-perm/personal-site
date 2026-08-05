package dev.eugene.publicationexporter.approved;

import java.nio.file.Path;

public final class ApprovedSnapshotWorkspaceConfinementException extends IllegalStateException {

    ApprovedSnapshotWorkspaceConfinementException(Path candidate, Path resolvedCandidate, Path reviewRoot) {
        super("Approved directory escapes review root: " + candidate
                + " resolved to " + resolvedCandidate + " outside " + reviewRoot);
    }
}
