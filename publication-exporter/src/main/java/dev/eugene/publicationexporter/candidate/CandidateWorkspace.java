package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface CandidateWorkspace {

    default void install(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(assets, "assets");
        if (!assets.isEmpty()) {
            throw new UnsupportedOperationException(
                    "This CandidateWorkspace implementation (" + getClass()
                            + ") does not support installing assets yet.");
        }
        install(identity, content.ruBody(), content.enBody(), content.ruTitle(), content.enTitle(),
                content.ruDescription(), content.enDescription(), content.referenceMap());
    }

    default void install(PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription, ReferenceMap referenceMap) {
        install(identity, CandidateSnapshot.of(ruBody, enBody, ruTitle, enTitle,
                ruDescription, enDescription, referenceMap), List.of());
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
