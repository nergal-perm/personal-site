package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

public final class ReleaseAlreadyExistsException extends IllegalStateException {

    public ReleaseAlreadyExistsException(PublicationIdentity identity) {
        super("A release already exists for " + identity);
    }
}
