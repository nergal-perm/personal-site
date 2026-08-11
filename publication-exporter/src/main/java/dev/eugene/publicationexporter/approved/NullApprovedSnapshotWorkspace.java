package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NullApprovedSnapshotWorkspace implements ApprovedSnapshotWorkspace {

    private final Map<PublicationIdentity, CandidateSnapshot> installed = new HashMap<>();

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription, ReferenceMap referenceMap) {
        validateInstallArguments(identity, ruBody, enBody, ruTitle, enTitle,
                ruDescription, enDescription, referenceMap);
        installed.put(identity, CandidateSnapshot.of(ruBody, enBody,
                List.of(PublicField.of("title", ruTitle), PublicField.of("description", ruDescription)),
                List.of(PublicField.of("title", enTitle), PublicField.of("description", enDescription)),
                "", referenceMap));
    }

    @Override
    public void install(PublicationIdentity identity, CandidateSnapshot content) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(content, "content");
        installed.put(identity, content);
    }

    private void validateInstallArguments(
            PublicationIdentity identity, String ruBody, String enBody, String ruTitle, String enTitle,
            String ruDescription, String enDescription, ReferenceMap referenceMap) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(ruTitle, "ruTitle");
        Objects.requireNonNull(enTitle, "enTitle");
        Objects.requireNonNull(ruDescription, "ruDescription");
        Objects.requireNonNull(enDescription, "enDescription");
        Objects.requireNonNull(referenceMap, "referenceMap");
    }

    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        validateIdentity(identity);
        return Optional.ofNullable(installed.get(identity))
                .filter(snapshot -> snapshot.referenceMap().identity().equals(identity));
    }

    @Override
    public Optional<CandidatePaths> find(PublicationIdentity identity) {
        validateIdentity(identity);
        if (!hasInstallation(identity)) {
            return Optional.empty();
        }
        return Optional.of(pathsFor(identity));
    }

    private void validateIdentity(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
    }

    private boolean hasInstallation(PublicationIdentity identity) {
        return installed.containsKey(identity);
    }

    private CandidatePaths pathsFor(PublicationIdentity identity) {
        Path approvedDirectory = Path.of("/approved", identity.publicCollection(), identity.publicId(), "approved");
        return CandidatePaths.of(approvedDirectory.resolve("ru.md"), approvedDirectory.resolve("en.md"));
    }
}
