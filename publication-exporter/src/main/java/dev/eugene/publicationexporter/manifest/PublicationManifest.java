package dev.eugene.publicationexporter.manifest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

public final class PublicationManifest {

    private final boolean ok;
    private final List<ManifestEntry> entries;

    private PublicationManifest(boolean ok, List<ManifestEntry> entries) {
        this.ok = ok;
        this.entries = List.copyOf(entries);
    }

    public static PublicationManifest of(List<ManifestEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        boolean ok = entries.stream().allMatch(ManifestEntry::admitted);
        return new PublicationManifest(ok, entries);
    }

    @JsonProperty("ok")
    public boolean ok() {
        return ok;
    }

    @JsonProperty("entries")
    public List<ManifestEntry> entries() {
        return entries;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PublicationManifest that)) {
            return false;
        }
        return ok == that.ok && entries.equals(that.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ok, entries);
    }

    @Override
    public String toString() {
        return "PublicationManifest[ok=" + ok + ", entries=" + entries + "]";
    }
}
