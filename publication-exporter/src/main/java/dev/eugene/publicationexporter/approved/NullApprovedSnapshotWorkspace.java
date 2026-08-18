package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NullApprovedSnapshotWorkspace implements ApprovedSnapshotWorkspace {

    private final Map<PublicationIdentity, CandidateSnapshot> installed = new HashMap<>();

    @Override
    public void install(PublicationIdentity identity, CandidateSnapshot snapshot) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(snapshot, "snapshot");
        installed.put(identity, snapshot);
    }

    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        validateIdentity(identity);
        return Optional.ofNullable(installed.get(identity))
                .filter(snapshot -> snapshot.referenceMap().identity().equals(identity));
    }

    @Override
    public Optional<CandidateSnapshot> findBySourceId(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        return installed.values().stream()
                .filter(snapshot -> snapshot.referenceMap().sourceId().filter(sourceId::equals).isPresent())
                .findFirst();
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
