package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public interface ApprovedSnapshotWorkspace {

    void install(PublicationIdentity identity, CandidateSnapshot snapshot);

    default void install(PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription, ReferenceMap referenceMap) {
        Objects.requireNonNull(identity, "identity");
        install(identity, LegacyApprovedSnapshotInstaller.snapshot(
                ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap));
    }

    Optional<CandidatePaths> find(PublicationIdentity identity);

    Optional<CandidateSnapshot> read(PublicationIdentity identity);

    Optional<CandidateSnapshot> findBySourceId(String sourceId);

    default <T> T withApprovalLock(PublicationIdentity identity, Supplier<T> operation) {
        Objects.requireNonNull(identity, "identity");
        return Objects.requireNonNull(operation, "operation").get();
    }

    static ApprovedSnapshotWorkspace create(Path reviewRoot) {
        return new FilesystemApprovedSnapshotWorkspace(reviewRoot);
    }

    static ApprovedSnapshotWorkspace createNull() {
        return new NullApprovedSnapshotWorkspace();
    }
}
