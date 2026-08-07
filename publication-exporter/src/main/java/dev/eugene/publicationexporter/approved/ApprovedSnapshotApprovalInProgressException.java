package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

public final class ApprovedSnapshotApprovalInProgressException extends ApprovedSnapshotWorkspaceStateException {

    public ApprovedSnapshotApprovalInProgressException(PublicationIdentity identity) {
        super("Another mark-reviewed process is already replacing the approved snapshot for " + identity + ".");
    }
}
