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
    private final String ruFieldsHash;
    private final String enFieldsHash;
    private final String structuredDataHash;

    private ReferenceMap(
            PublicationIdentity identity,
            String ruHash,
            String enHash,
            String ruFieldsHash,
            String enFieldsHash,
            String structuredDataHash) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.ruHash = Objects.requireNonNull(ruHash, "ruHash");
        this.enHash = Objects.requireNonNull(enHash, "enHash");
        this.ruFieldsHash = Objects.requireNonNull(ruFieldsHash, "ruFieldsHash");
        this.enFieldsHash = Objects.requireNonNull(enFieldsHash, "enFieldsHash");
        this.structuredDataHash = Objects.requireNonNull(structuredDataHash, "structuredDataHash");
    }

    public static ReferenceMap empty(
            PublicationIdentity identity,
            String ruHash,
            String enHash,
            String ruFieldsHash,
            String enFieldsHash,
            String structuredDataHash) {
        return new ReferenceMap(identity, ruHash, enHash,
                ruFieldsHash, enFieldsHash, structuredDataHash);
    }

    @Deprecated
    public static ReferenceMap empty(
            PublicationIdentity identity,
            String ruHash,
            String enHash,
            String ruTitleHash,
            String enTitleHash,
            String ruDescriptionHash,
            String enDescriptionHash) {
        return empty(identity, ruHash, enHash,
                ruTitleHash + ":" + ruDescriptionHash,
                enTitleHash + ":" + enDescriptionHash, "");
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

    @JsonProperty("ruFieldsHash")
    public String ruFieldsHash() {
        return ruFieldsHash;
    }

    @JsonProperty("enFieldsHash")
    public String enFieldsHash() {
        return enFieldsHash;
    }

    @JsonProperty("structuredDataHash")
    public String structuredDataHash() {
        return structuredDataHash;
    }

    public boolean sameContentAs(ReferenceMap other) {
        Objects.requireNonNull(other, "other");
        return ruHash.equals(other.ruHash)
                && enHash.equals(other.enHash)
                && ruFieldsHash.equals(other.ruFieldsHash)
                && enFieldsHash.equals(other.enFieldsHash)
                && structuredDataHash.equals(other.structuredDataHash);
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
                && ruFieldsHash.equals(that.ruFieldsHash)
                && enFieldsHash.equals(that.enFieldsHash)
                && structuredDataHash.equals(that.structuredDataHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, ruHash, enHash,
                ruFieldsHash, enFieldsHash, structuredDataHash);
    }

    @Override
    public String toString() {
        return "ReferenceMap[identity=" + identity
                + ", ruHash=" + ruHash
                + ", enHash=" + enHash
                + ", ruFieldsHash=" + ruFieldsHash
                + ", enFieldsHash=" + enFieldsHash
                + ", structuredDataHash=" + structuredDataHash + "]";
    }
}
