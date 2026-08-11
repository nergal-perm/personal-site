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
    public void install(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(assets, "assets");
        installed.add(InstalledCandidate.of(identity, content, assets));
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
                .filter(candidate -> candidate.content().referenceMap().identity().equals(identity))
                .map(InstalledCandidate::content);
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
        private final CandidateSnapshot content;
        private final List<CandidateAsset> assets;

        private InstalledCandidate(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.content = Objects.requireNonNull(content, "content");
            this.assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
        }

        public static InstalledCandidate of(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets) {
            return new InstalledCandidate(identity, content, assets);
        }

        public PublicationIdentity identity() {
            return identity;
        }

        public CandidateSnapshot content() {
            return content;
        }

        public List<CandidateAsset> assets() {
            return assets;
        }

        public String ruBody() {
            return content.ruBody();
        }

        public String enBody() {
            return content.enBody();
        }

        public String ruTitle() {
            return content.ruTitle();
        }

        public String enTitle() {
            return content.enTitle();
        }

        public String ruDescription() {
            return content.ruDescription();
        }

        public String enDescription() {
            return content.enDescription();
        }

        public ReferenceMap referenceMap() {
            return content.referenceMap();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof InstalledCandidate that)) {
                return false;
            }
            return identity.equals(that.identity) && content.equals(that.content) && assets.equals(that.assets);
        }

        @Override
        public int hashCode() {
            return Objects.hash(identity, content, assets);
        }

        @Override
        public String toString() {
            return "InstalledCandidate[identity=" + identity + ", content=" + content + ", assets=" + assets + "]";
        }
    }
}
