package dev.eugene.publicationexporter.buildfromreview;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.release.ReleaseProvenance;

import java.util.Objects;

public final class ReleaseResult {

    private final boolean ok;
    private final PublicationIdentity identity;
    private final ReleaseProvenance provenance;
    private final String message;

    private ReleaseResult(boolean ok, PublicationIdentity identity, ReleaseProvenance provenance, String message) {
        this.ok = ok;
        this.identity = identity;
        this.provenance = provenance;
        this.message = message;
    }

    public static ReleaseResult released(PublicationIdentity identity, ReleaseProvenance provenance) {
        return new ReleaseResult(
                true,
                Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(provenance, "provenance"),
                null);
    }

    public static ReleaseResult blocked(String message) {
        return new ReleaseResult(false, null, null, Objects.requireNonNull(message, "message"));
    }

    @JsonProperty("ok")
    public boolean ok() {
        return ok;
    }

    @JsonProperty("identity")
    public PublicationIdentity identity() {
        return identity;
    }

    @JsonProperty("provenance")
    public ReleaseProvenance provenance() {
        return provenance;
    }

    @JsonProperty("message")
    public String message() {
        return message;
    }
}
