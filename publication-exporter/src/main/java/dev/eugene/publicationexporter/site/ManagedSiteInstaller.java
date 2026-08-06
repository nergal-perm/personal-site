package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;

import java.nio.file.Path;

public interface ManagedSiteInstaller {

    void install(PublicationIdentity identity, CandidateSnapshot approvedSnapshot);

    static ManagedSiteInstaller createNull() {
        return new NullManagedSiteInstaller();
    }

    static ManagedSiteInstaller create(Path siteRoot) {
        return new FilesystemManagedSiteInstaller(siteRoot);
    }
}
