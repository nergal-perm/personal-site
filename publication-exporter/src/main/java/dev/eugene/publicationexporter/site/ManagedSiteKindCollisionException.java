package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.nio.file.Path;

public final class ManagedSiteKindCollisionException extends IllegalStateException {

    public ManagedSiteKindCollisionException(PublicationIdentity incoming, Path existingFile) {
        super("Cannot install site content for " + incoming
                + ": an existing managed file at the same (collection, publicId) belongs to a different kind, "
                + existingFile + ".");
    }
}
