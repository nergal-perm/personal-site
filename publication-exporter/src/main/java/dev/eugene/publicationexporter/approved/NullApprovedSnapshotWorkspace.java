package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NullApprovedSnapshotWorkspace implements ApprovedSnapshotWorkspace {

    private final Map<PublicationIdentity, InstalledApprovedSnapshot> installed = new HashMap<>();

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        validateInstallArguments(identity, ruBody, enBody, referenceMap);
        ensureNotAlreadyInstalled(identity);
        installed.put(identity, InstalledApprovedSnapshot.of(ruBody, enBody, referenceMap));
    }

    private void validateInstallArguments(
            PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(referenceMap, "referenceMap");
    }

    private void ensureNotAlreadyInstalled(PublicationIdentity identity) {
        if (installed.containsKey(identity)) {
            throw new ApprovedSnapshotAlreadyExistsException(identity);
        }
    }

    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        validateIdentity(identity);
        return Optional.ofNullable(installed.get(identity))
                .map(snapshot -> CandidateSnapshot.of(snapshot.ruBody(), snapshot.enBody(), snapshot.referenceMap()));
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

    private static final class InstalledApprovedSnapshot {

        private final String ruBody;
        private final String enBody;
        private final ReferenceMap referenceMap;

        private InstalledApprovedSnapshot(String ruBody, String enBody, ReferenceMap referenceMap) {
            this.ruBody = Objects.requireNonNull(ruBody, "ruBody");
            this.enBody = Objects.requireNonNull(enBody, "enBody");
            this.referenceMap = Objects.requireNonNull(referenceMap, "referenceMap");
        }

        static InstalledApprovedSnapshot of(String ruBody, String enBody, ReferenceMap referenceMap) {
            return new InstalledApprovedSnapshot(ruBody, enBody, referenceMap);
        }

        String ruBody() {
            return ruBody;
        }

        String enBody() {
            return enBody;
        }

        ReferenceMap referenceMap() {
            return referenceMap;
        }
    }
}
