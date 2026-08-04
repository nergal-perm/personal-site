package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.List;
import java.util.Objects;

public final class ReferenceMap {

    private static final int SCHEMA_VERSION = 1;

    private final PublicationIdentity identity;
    private final String ruHash;
    private final String enHash;

    private ReferenceMap(PublicationIdentity identity, String ruHash, String enHash) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.ruHash = Objects.requireNonNull(ruHash, "ruHash");
        this.enHash = Objects.requireNonNull(enHash, "enHash");
    }

    public static ReferenceMap empty(PublicationIdentity identity, String ruHash, String enHash) {
        return new ReferenceMap(identity, ruHash, enHash);
    }

    @JsonProperty("schemaVersion")
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    @JsonProperty("publicationIdentity")
    public PublicationIdentity identity() {
        return identity;
    }

    @JsonProperty("ruHash")
    public String ruHash() {
        return ruHash;
    }

    @JsonProperty("enHash")
    public String enHash() {
        return enHash;
    }

    @JsonProperty("occurrences")
    public List<Object> occurrences() {
        return List.of();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReferenceMap that)) {
            return false;
        }
        return identity.equals(that.identity) && ruHash.equals(that.ruHash) && enHash.equals(that.enHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, ruHash, enHash);
    }

    @Override
    public String toString() {
        return "ReferenceMap[identity=" + identity + ", ruHash=" + ruHash + ", enHash=" + enHash + "]";
    }
}
