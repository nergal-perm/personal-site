package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.Optional;

public interface CandidateWorkspace {

    void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap);

    Optional<CandidatePaths> find(PublicationIdentity identity);

    static CandidateWorkspace create(Path reviewRoot) {
        return new FilesystemCandidateWorkspace(reviewRoot);
    }

    static CandidateWorkspace createNull() {
        return new NullCandidateWorkspace();
    }
}
