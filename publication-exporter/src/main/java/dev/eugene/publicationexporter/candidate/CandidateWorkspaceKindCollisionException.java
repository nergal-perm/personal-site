package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

public final class CandidateWorkspaceKindCollisionException extends IllegalStateException {

    public CandidateWorkspaceKindCollisionException(PublicationIdentity incoming, PublicationIdentity existing) {
        super("Cannot install candidate for " + incoming
                + ": an existing candidate at the same (collection, publicId) belongs to a different kind, "
                + existing + ".");
    }
}
