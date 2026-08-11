package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface CandidateWorkspace {

    void install(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets);

    default void install(PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription, ReferenceMap referenceMap) {
        install(identity, CandidateSnapshot.of(
                ruBody, enBody,
                List.of(PublicField.of("title", ruTitle), PublicField.of("description", ruDescription)),
                List.of(PublicField.of("title", enTitle), PublicField.of("description", enDescription)),
                "", referenceMap), List.of());
    }

    default void install(PublicationIdentity identity, String ruBody, String enBody,
            List<PublicField> ruFields, List<PublicField> enFields, String structuredData,
            ReferenceMap referenceMap) {
        install(identity, CandidateSnapshot.of(ruBody, enBody, ruFields, enFields, structuredData, referenceMap),
                List.of());
    }

    Optional<CandidatePaths> find(PublicationIdentity identity);

    Optional<CandidateSnapshot> read(PublicationIdentity identity);

    static CandidateWorkspace create(Path reviewRoot) {
        return new FilesystemCandidateWorkspace(reviewRoot);
    }

    static CandidateWorkspace createNull() {
        return new NullCandidateWorkspace();
    }
}
