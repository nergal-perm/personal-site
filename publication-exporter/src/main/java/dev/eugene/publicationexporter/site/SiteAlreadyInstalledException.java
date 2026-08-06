package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

public final class SiteAlreadyInstalledException extends IllegalStateException {

    public SiteAlreadyInstalledException(PublicationIdentity identity) {
        super("A site installation already exists for " + identity);
    }
}
