package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.Optional;

public interface ApprovedSnapshotWorkspace {

    void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap);

    Optional<CandidatePaths> find(PublicationIdentity identity);

    static ApprovedSnapshotWorkspace createNull() {
        return new NullApprovedSnapshotWorkspace();
    }
}
