package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public interface ApprovedSnapshotWorkspace {

    void install(PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription, ReferenceMap referenceMap);

    default void install(PublicationIdentity identity, CandidateSnapshot content) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(content, "content");
        if (!content.structuredData().isEmpty()) {
            throw new UnsupportedOperationException(
                    "Approved snapshot workspace does not support structured data.");
        }
        install(identity, content.ruBody(), content.enBody(),
                PublicField.value(content.ruFields(), "title").orElseThrow(),
                PublicField.value(content.enFields(), "title").orElseThrow(),
                PublicField.value(content.ruFields(), "description").orElseThrow(),
                PublicField.value(content.enFields(), "description").orElseThrow(),
                content.referenceMap());
    }

    Optional<CandidatePaths> find(PublicationIdentity identity);

    Optional<CandidateSnapshot> read(PublicationIdentity identity);

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
