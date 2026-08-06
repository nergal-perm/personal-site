package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.Optional;

public interface ApprovedSnapshotWorkspace {

    void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap);

    Optional<CandidatePaths> find(PublicationIdentity identity);

    Optional<CandidateSnapshot> read(PublicationIdentity identity);

    static ApprovedSnapshotWorkspace create(Path reviewRoot) {
        return new FilesystemApprovedSnapshotWorkspace(reviewRoot);
    }

    static ApprovedSnapshotWorkspace createNull() {
        return new NullApprovedSnapshotWorkspace();
    }
}
