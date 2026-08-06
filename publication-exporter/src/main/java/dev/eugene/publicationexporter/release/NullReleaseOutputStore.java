package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class NullReleaseOutputStore implements ReleaseOutputStore {

    private final Map<PublicationIdentity, InstalledRelease> installed = new HashMap<>();

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReleaseProvenance provenance) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(enBody, "enBody");
        Objects.requireNonNull(provenance, "provenance");
        if (installed.containsKey(identity)) {
            throw new ReleaseAlreadyExistsException(identity);
        }
        installed.put(identity, InstalledRelease.of(ruBody, enBody, provenance));
    }

    public Map<PublicationIdentity, InstalledRelease> installed() {
        return Map.copyOf(installed);
    }

    public static final class InstalledRelease {

        private final String ruBody;
        private final String enBody;
        private final ReleaseProvenance provenance;

        private InstalledRelease(String ruBody, String enBody, ReleaseProvenance provenance) {
            this.ruBody = Objects.requireNonNull(ruBody, "ruBody");
            this.enBody = Objects.requireNonNull(enBody, "enBody");
            this.provenance = Objects.requireNonNull(provenance, "provenance");
        }

        static InstalledRelease of(String ruBody, String enBody, ReleaseProvenance provenance) {
            return new InstalledRelease(ruBody, enBody, provenance);
        }

        public String ruBody() {
            return ruBody;
        }

        public String enBody() {
            return enBody;
        }

        public ReleaseProvenance provenance() {
            return provenance;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof InstalledRelease that)) {
                return false;
            }
            return ruBody.equals(that.ruBody)
                    && enBody.equals(that.enBody)
                    && provenance.equals(that.provenance);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ruBody, enBody, provenance);
        }

        @Override
        public String toString() {
            return "InstalledRelease[ruBody=" + ruBody + ", enBody=" + enBody + ", provenance=" + provenance + "]";
        }
    }
}
