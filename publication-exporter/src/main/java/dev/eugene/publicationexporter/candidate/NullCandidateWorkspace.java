package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NullCandidateWorkspace implements CandidateWorkspace {

    private final List<InstalledCandidate> installed = new ArrayList<>();

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        installed.add(InstalledCandidate.of(identity, ruBody, enBody, referenceMap));
    }

    public List<InstalledCandidate> installed() {
        return List.copyOf(installed);
    }

    public static final class InstalledCandidate {

        private final PublicationIdentity identity;
        private final String ruBody;
        private final String enBody;
        private final ReferenceMap referenceMap;

        private InstalledCandidate(
                PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.ruBody = Objects.requireNonNull(ruBody, "ruBody");
            this.enBody = Objects.requireNonNull(enBody, "enBody");
            this.referenceMap = Objects.requireNonNull(referenceMap, "referenceMap");
        }

        public static InstalledCandidate of(
                PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
            return new InstalledCandidate(identity, ruBody, enBody, referenceMap);
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
                    && referenceMap.equals(that.referenceMap);
        }

        @Override
        public int hashCode() {
            return Objects.hash(identity, ruBody, enBody, referenceMap);
        }

        @Override
        public String toString() {
            return "InstalledCandidate[identity=" + identity
                    + ", ruBody=" + ruBody + ", enBody=" + enBody + ", referenceMap=" + referenceMap + "]";
        }
    }
}
