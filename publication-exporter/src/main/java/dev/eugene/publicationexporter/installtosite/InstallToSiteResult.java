package dev.eugene.publicationexporter.installtosite;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.Objects;

public final class InstallToSiteResult {

    private final boolean ok;
    private final PublicationIdentity identity;
    private final String message;

    private InstallToSiteResult(boolean ok, PublicationIdentity identity, String message) {
        this.ok = ok;
        this.identity = identity;
        this.message = message;
    }

    public static InstallToSiteResult installed(PublicationIdentity identity) {
        return new InstallToSiteResult(true, Objects.requireNonNull(identity, "identity"), null);
    }

    public static InstallToSiteResult blocked(String message) {
        return new InstallToSiteResult(false, null, Objects.requireNonNull(message, "message"));
    }

    @JsonProperty("ok")
    public boolean ok() {
        return ok;
    }

    @JsonProperty("identity")
    public PublicationIdentity identity() {
        return identity;
    }

    @JsonProperty("message")
    public String message() {
        return message;
    }
}
