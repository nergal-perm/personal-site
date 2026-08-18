package dev.eugene.publicationexporter.release;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.Objects;

public final class ReleaseProvenance {

    private static final int CONTRACT_EDITION = 1;

    private final PublicationIdentity identity;
    private final String approvedRuHash;
    private final String approvedEnHash;
    private final String outputRuHash;
    private final String outputEnHash;
    private final int activationCount;
    private final int deactivationCount;

    private ReleaseProvenance(PublicationIdentity identity, String approvedRuHash, String approvedEnHash,
            String outputRuHash, String outputEnHash, int activationCount, int deactivationCount) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.approvedRuHash = Objects.requireNonNull(approvedRuHash, "approvedRuHash");
        this.approvedEnHash = Objects.requireNonNull(approvedEnHash, "approvedEnHash");
        this.outputRuHash = Objects.requireNonNull(outputRuHash, "outputRuHash");
        this.outputEnHash = Objects.requireNonNull(outputEnHash, "outputEnHash");
        this.activationCount = activationCount;
        this.deactivationCount = deactivationCount;
    }

    public static ReleaseProvenance of(PublicationIdentity identity, String approvedRuHash, String approvedEnHash,
            String outputRuHash, String outputEnHash) {
        return new ReleaseProvenance(identity, approvedRuHash, approvedEnHash, outputRuHash, outputEnHash, 0, 0);
    }

    public static ReleaseProvenance of(PublicationIdentity identity, String approvedRuHash, String approvedEnHash,
            String outputRuHash, String outputEnHash, int activationCount, int deactivationCount) {
        return new ReleaseProvenance(identity, approvedRuHash, approvedEnHash,
                outputRuHash, outputEnHash, activationCount, deactivationCount);
    }

    @JsonProperty("contractEdition")
    public int contractEdition() {
        return CONTRACT_EDITION;
    }

    @JsonProperty("publicationIdentity")
    public PublicationIdentity identity() {
        return identity;
    }

    @JsonProperty("approvedRuHash")
    public String approvedRuHash() {
        return approvedRuHash;
    }

    @JsonProperty("approvedEnHash")
    public String approvedEnHash() {
        return approvedEnHash;
    }

    @JsonProperty("outputRuHash")
    public String outputRuHash() {
        return outputRuHash;
    }

    @JsonProperty("outputEnHash")
    public String outputEnHash() {
        return outputEnHash;
    }

    @JsonProperty("activationCount")
    public int activationCount() {
        return activationCount;
    }

    @JsonProperty("deactivationCount")
    public int deactivationCount() {
        return deactivationCount;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReleaseProvenance that)) {
            return false;
        }
        return identity.equals(that.identity)
                && approvedRuHash.equals(that.approvedRuHash)
                && approvedEnHash.equals(that.approvedEnHash)
                && outputRuHash.equals(that.outputRuHash)
                && outputEnHash.equals(that.outputEnHash)
                && activationCount == that.activationCount
                && deactivationCount == that.deactivationCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, approvedRuHash, approvedEnHash, outputRuHash, outputEnHash,
                activationCount, deactivationCount);
    }

    @Override
    public String toString() {
        return "ReleaseProvenance[identity=" + identity
                + ", approvedRuHash=" + approvedRuHash + ", approvedEnHash=" + approvedEnHash
                + ", outputRuHash=" + outputRuHash + ", outputEnHash=" + outputEnHash
                + ", activationCount=" + activationCount + ", deactivationCount=" + deactivationCount + "]";
    }
}
