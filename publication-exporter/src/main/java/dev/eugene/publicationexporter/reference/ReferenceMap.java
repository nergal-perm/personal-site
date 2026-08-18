package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ReferenceMap {

    private static final int SCHEMA_VERSION = 1;

    private final PublicationIdentity identity;
    private final Optional<String> sourceId;
    private final String ruHash;
    private final String enHash;
    private final String ruFieldsHash;
    private final String enFieldsHash;
    private final String structuredDataHash;
    private final List<Occurrence> occurrences;
    private final String sourceBodyHash;

    private ReferenceMap(
            PublicationIdentity identity,
            Optional<String> sourceId,
            String ruHash,
            String enHash,
            String ruFieldsHash,
            String enFieldsHash,
            String structuredDataHash,
            List<Occurrence> occurrences,
            String sourceBodyHash) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.ruHash = Objects.requireNonNull(ruHash, "ruHash");
        this.enHash = Objects.requireNonNull(enHash, "enHash");
        this.ruFieldsHash = Objects.requireNonNull(ruFieldsHash, "ruFieldsHash");
        this.enFieldsHash = Objects.requireNonNull(enFieldsHash, "enFieldsHash");
        this.structuredDataHash = Objects.requireNonNull(structuredDataHash, "structuredDataHash");
        this.occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
        this.sourceBodyHash = Objects.requireNonNull(sourceBodyHash, "sourceBodyHash");
    }

    public static ReferenceMap of(
            PublicationIdentity identity,
            String sourceId,
            String ruHash,
            String enHash,
            String ruFieldsHash,
            String enFieldsHash,
            String structuredDataHash,
            List<Occurrence> occurrences) {
        return new ReferenceMap(identity, Optional.of(Objects.requireNonNull(sourceId, "sourceId")),
                ruHash, enHash, ruFieldsHash, enFieldsHash, structuredDataHash, occurrences, "");
    }

    public static ReferenceMap of(
            PublicationIdentity identity,
            String sourceId,
            String ruHash,
            String enHash,
            String ruFieldsHash,
            String enFieldsHash,
            String structuredDataHash,
            List<Occurrence> occurrences,
            String sourceBodyHash) {
        return new ReferenceMap(identity, Optional.of(Objects.requireNonNull(sourceId, "sourceId")),
                ruHash, enHash, ruFieldsHash, enFieldsHash, structuredDataHash, occurrences, sourceBodyHash);
    }

    public static ReferenceMap of(
            PublicationIdentity identity,
            String ruHash,
            String enHash,
            String ruFieldsHash,
            String enFieldsHash,
            String structuredDataHash,
            List<Occurrence> occurrences) {
        return new ReferenceMap(identity, Optional.empty(),
                ruHash, enHash, ruFieldsHash, enFieldsHash, structuredDataHash, occurrences, "");
    }

    public static ReferenceMap empty(
            PublicationIdentity identity,
            String ruHash,
            String enHash,
            String ruFieldsHash,
            String enFieldsHash,
            String structuredDataHash) {
        return of(identity, ruHash, enHash,
                ruFieldsHash, enFieldsHash, structuredDataHash, List.of());
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

    @JsonProperty("sourceId")
    public Optional<String> sourceId() {
        return sourceId;
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

    @JsonProperty("sourceBodyHash")
    public String sourceBodyHash() {
        return sourceBodyHash;
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
    public List<Occurrence> occurrences() {
        return occurrences;
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
                && sourceId.equals(that.sourceId)
                && ruHash.equals(that.ruHash)
                && enHash.equals(that.enHash)
                && ruFieldsHash.equals(that.ruFieldsHash)
                && enFieldsHash.equals(that.enFieldsHash)
                && structuredDataHash.equals(that.structuredDataHash)
                && occurrences.equals(that.occurrences)
                && sourceBodyHash.equals(that.sourceBodyHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, sourceId, ruHash, enHash,
                ruFieldsHash, enFieldsHash, structuredDataHash, occurrences, sourceBodyHash);
    }

    @Override
    public String toString() {
        return "ReferenceMap[identity=" + identity
                + ", ruHash=" + ruHash
                + ", enHash=" + enHash
                + ", sourceId=" + sourceId
                + ", ruFieldsHash=" + ruFieldsHash
                + ", enFieldsHash=" + enFieldsHash
                + ", structuredDataHash=" + structuredDataHash
                + ", occurrences=" + occurrences
                + ", sourceBodyHash=" + sourceBodyHash + "]";
    }
}
