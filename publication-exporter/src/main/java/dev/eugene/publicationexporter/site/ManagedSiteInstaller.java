package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;

public interface ManagedSiteInstaller {

    void install(PublicationIdentity identity, CandidateSnapshot approvedSnapshot);

    static ManagedSiteInstaller createNull() {
        return new NullManagedSiteInstaller();
    }
}
