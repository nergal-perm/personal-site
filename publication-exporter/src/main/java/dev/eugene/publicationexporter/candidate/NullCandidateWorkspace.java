package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class NullCandidateWorkspace implements CandidateWorkspace {

    private final List<InstalledCandidate> installed = new ArrayList<>();

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription, ReferenceMap referenceMap) {
        installed.add(InstalledCandidate.of(identity, ruBody, enBody, ruTitle, enTitle,
                ruDescription, enDescription, referenceMap));
    }

    @Override
    public Optional<CandidatePaths> find(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return lastInstalledMatching(identity).map(NullCandidateWorkspace::syntheticPaths);
    }

    @Override
    public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return lastInstalledMatching(identity)
                .filter(candidate -> candidate.referenceMap().identity().equals(identity))
                .map(candidate -> CandidateSnapshot.of(candidate.ruBody(), candidate.enBody(), candidate.ruTitle(),
                        candidate.enTitle(), candidate.ruDescription(), candidate.enDescription(),
                        candidate.referenceMap()));
    }

    private Optional<InstalledCandidate> lastInstalledMatching(PublicationIdentity identity) {
        InstalledCandidate match = null;
        for (InstalledCandidate candidate : installed) {
            if (candidate.identity().equals(identity)) {
                match = candidate;
            }
        }
        return Optional.ofNullable(match);
    }

    private static CandidatePaths syntheticPaths(InstalledCandidate candidate) {
        Path candidateDirectory = Path.of("/candidate", candidate.identity().publicCollection(),
                candidate.identity().publicId(), "candidate");
        return CandidatePaths.of(candidateDirectory.resolve("ru.md"), candidateDirectory.resolve("en.md"));
    }

    public List<InstalledCandidate> installed() {
        return List.copyOf(installed);
    }

    public static final class InstalledCandidate {

        private final PublicationIdentity identity;
        private final String ruBody;
        private final String enBody;
        private final String ruTitle;
        private final String enTitle;
        private final String ruDescription;
        private final String enDescription;
        private final ReferenceMap referenceMap;

        private InstalledCandidate(
                PublicationIdentity identity, String ruBody, String enBody, String ruTitle, String enTitle,
                String ruDescription, String enDescription, ReferenceMap referenceMap) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.ruBody = Objects.requireNonNull(ruBody, "ruBody");
            this.enBody = Objects.requireNonNull(enBody, "enBody");
            this.ruTitle = Objects.requireNonNull(ruTitle, "ruTitle");
            this.enTitle = Objects.requireNonNull(enTitle, "enTitle");
            this.ruDescription = Objects.requireNonNull(ruDescription, "ruDescription");
            this.enDescription = Objects.requireNonNull(enDescription, "enDescription");
            this.referenceMap = Objects.requireNonNull(referenceMap, "referenceMap");
        }

        public static InstalledCandidate of(
                PublicationIdentity identity, String ruBody, String enBody, String ruTitle, String enTitle,
                String ruDescription, String enDescription, ReferenceMap referenceMap) {
            return new InstalledCandidate(identity, ruBody, enBody, ruTitle, enTitle,
                    ruDescription, enDescription, referenceMap);
        }

        public PublicationIdentity identity() {
            return identity;
        }

        public String ruBody() {
            return ruBody;
        }

        public String enBody() {
            return enBody;
        }

        public String ruTitle() {
            return ruTitle;
        }

        public String enTitle() {
            return enTitle;
        }

        public String ruDescription() {
            return ruDescription;
        }

        public String enDescription() {
            return enDescription;
        }

        public ReferenceMap referenceMap() {
            return referenceMap;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof InstalledCandidate that)) {
                return false;
            }
            return identity.equals(that.identity)
                    && ruBody.equals(that.ruBody)
                    && enBody.equals(that.enBody)
                    && ruTitle.equals(that.ruTitle)
                    && enTitle.equals(that.enTitle)
                    && ruDescription.equals(that.ruDescription)
                    && enDescription.equals(that.enDescription)
                    && referenceMap.equals(that.referenceMap);
        }

        @Override
        public int hashCode() {
            return Objects.hash(identity, ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap);
        }

        @Override
        public String toString() {
            return "InstalledCandidate[identity=" + identity
                    + ", ruBody=" + ruBody + ", enBody=" + enBody
                    + ", ruTitle=" + ruTitle + ", enTitle=" + enTitle
                    + ", ruDescription=" + ruDescription + ", enDescription=" + enDescription
                    + ", referenceMap=" + referenceMap + "]";
        }
    }
}
