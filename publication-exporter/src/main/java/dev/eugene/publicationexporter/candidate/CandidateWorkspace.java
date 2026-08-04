package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;

public interface CandidateWorkspace {

    void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap);

    static CandidateWorkspace createNull() {
        return new NullCandidateWorkspace();
    }
}
