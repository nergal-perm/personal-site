package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public final class PublicationIdentity {

    private final String publicCollection;
    private final String publicContentType;
    private final String publicId;

    private PublicationIdentity(String publicCollection, String publicContentType, String publicId) {
        this.publicCollection = Objects.requireNonNull(publicCollection, "publicCollection");
        this.publicContentType = Objects.requireNonNull(publicContentType, "publicContentType");
        this.publicId = Objects.requireNonNull(publicId, "publicId");
    }

    public static PublicationIdentity of(String publicCollection, String publicContentType, String publicId) {
        return new PublicationIdentity(publicCollection, publicContentType, publicId);
    }

    @JsonProperty("publicCollection")
    public String publicCollection() {
        return publicCollection;
    }

    @JsonProperty("publicContentType")
    public String publicContentType() {
        return publicContentType;
    }

    @JsonProperty("publicId")
    public String publicId() {
        return publicId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PublicationIdentity that)) {
            return false;
        }
        return publicCollection.equals(that.publicCollection)
                && publicContentType.equals(that.publicContentType)
                && publicId.equals(that.publicId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(publicCollection, publicContentType, publicId);
    }

    @Override
    public String toString() {
        return "PublicationIdentity[publicCollection=" + publicCollection
                + ", publicContentType=" + publicContentType + ", publicId=" + publicId + "]";
    }
}
