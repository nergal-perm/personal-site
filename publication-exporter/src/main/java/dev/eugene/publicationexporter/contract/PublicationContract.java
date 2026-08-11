package dev.eugene.publicationexporter.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

public final class PublicationContract {

    private final int contractVersion;
    private final List<KindContract> kinds;

    private PublicationContract(int contractVersion, List<KindContract> kinds) {
        this.contractVersion = contractVersion;
        this.kinds = List.copyOf(Objects.requireNonNull(kinds, "kinds"));
    }

    public static PublicationContract of(int contractVersion, List<KindContract> kinds) {
        return new PublicationContract(contractVersion, kinds);
    }

    @JsonProperty("contractVersion")
    public int contractVersion() {
        return contractVersion;
    }

    @JsonProperty("kinds")
    public List<KindContract> kinds() {
        return kinds;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PublicationContract that)) {
            return false;
        }
        return contractVersion == that.contractVersion && kinds.equals(that.kinds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contractVersion, kinds);
    }

    @Override
    public String toString() {
        return "PublicationContract[contractVersion=" + contractVersion + ", kinds=" + kinds + "]";
    }
}
