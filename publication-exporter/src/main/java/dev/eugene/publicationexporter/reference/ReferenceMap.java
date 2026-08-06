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
    private final String ruTitleHash;
    private final String enTitleHash;
    private final String ruDescriptionHash;
    private final String enDescriptionHash;

    private ReferenceMap(
            PublicationIdentity identity,
            String ruHash,
            String enHash,
            String ruTitleHash,
            String enTitleHash,
            String ruDescriptionHash,
            String enDescriptionHash) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.ruHash = Objects.requireNonNull(ruHash, "ruHash");
        this.enHash = Objects.requireNonNull(enHash, "enHash");
        this.ruTitleHash = Objects.requireNonNull(ruTitleHash, "ruTitleHash");
        this.enTitleHash = Objects.requireNonNull(enTitleHash, "enTitleHash");
        this.ruDescriptionHash = Objects.requireNonNull(ruDescriptionHash, "ruDescriptionHash");
        this.enDescriptionHash = Objects.requireNonNull(enDescriptionHash, "enDescriptionHash");
    }

    public static ReferenceMap empty(
            PublicationIdentity identity,
            String ruHash,
            String enHash,
            String ruTitleHash,
            String enTitleHash,
            String ruDescriptionHash,
            String enDescriptionHash) {
        return new ReferenceMap(identity, ruHash, enHash,
                ruTitleHash, enTitleHash, ruDescriptionHash, enDescriptionHash);
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

    @JsonProperty("ruTitleHash")
    public String ruTitleHash() {
        return ruTitleHash;
    }

    @JsonProperty("enTitleHash")
    public String enTitleHash() {
        return enTitleHash;
    }

    @JsonProperty("ruDescriptionHash")
    public String ruDescriptionHash() {
        return ruDescriptionHash;
    }

    @JsonProperty("enDescriptionHash")
    public String enDescriptionHash() {
        return enDescriptionHash;
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
        return identity.equals(that.identity)
                && ruHash.equals(that.ruHash)
                && enHash.equals(that.enHash)
                && ruTitleHash.equals(that.ruTitleHash)
                && enTitleHash.equals(that.enTitleHash)
                && ruDescriptionHash.equals(that.ruDescriptionHash)
                && enDescriptionHash.equals(that.enDescriptionHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, ruHash, enHash,
                ruTitleHash, enTitleHash, ruDescriptionHash, enDescriptionHash);
    }

    @Override
    public String toString() {
        return "ReferenceMap[identity=" + identity
                + ", ruHash=" + ruHash
                + ", enHash=" + enHash
                + ", ruTitleHash=" + ruTitleHash
                + ", enTitleHash=" + enTitleHash
                + ", ruDescriptionHash=" + ruDescriptionHash
                + ", enDescriptionHash=" + enDescriptionHash + "]";
    }
}
