package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

public final class ApprovedSnapshotAlreadyExistsException extends IllegalStateException {

    public ApprovedSnapshotAlreadyExistsException(PublicationIdentity identity) {
        super("An approved snapshot already exists for " + identity);
    }
}
