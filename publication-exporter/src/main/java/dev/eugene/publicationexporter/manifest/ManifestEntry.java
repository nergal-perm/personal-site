package dev.eugene.publicationexporter.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ManifestEntry {

    private final String path;
    private final PublicationIdentity identity;
    private final List<Diagnostic> diagnostics;

    private ManifestEntry(String path, PublicationIdentity identity, List<Diagnostic> diagnostics) {
        this.path = Objects.requireNonNull(path, "path");
        this.identity = identity;
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public static ManifestEntry admitted(String path, PublicationIdentity identity) {
        return new ManifestEntry(path, Objects.requireNonNull(identity, "identity"), List.of());
    }

    public static ManifestEntry blocked(String path, List<Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            throw new IllegalArgumentException("blocked() requires at least one diagnostic");
        }
        return new ManifestEntry(path, null, diagnostics);
    }

    @JsonProperty("path")
    public String path() {
        return path;
    }

    @JsonProperty("identity")
    public PublicationIdentity identity() {
        return identity;
    }

    @JsonProperty("diagnostics")
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public boolean admitted() {
        return diagnostics.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ManifestEntry that)) {
            return false;
        }
        return path.equals(that.path) && Objects.equals(identity, that.identity)
                && diagnostics.equals(that.diagnostics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, identity, diagnostics);
    }

    @Override
    public String toString() {
        return "ManifestEntry[path=" + path + ", identity=" + identity + ", diagnostics=" + diagnostics + "]";
    }
}
