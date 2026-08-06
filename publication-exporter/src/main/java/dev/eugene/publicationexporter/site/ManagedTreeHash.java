package dev.eugene.publicationexporter.site;

import java.util.Objects;

public final class ManagedTreeHash {
    private final String relative;
    private final String sha256;

    private ManagedTreeHash(String relative, String sha256) {
        this.relative = Objects.requireNonNull(relative, "relative");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
    }

    public static ManagedTreeHash of(String relative, String sha256) {
        return new ManagedTreeHash(relative, sha256);
    }

    public String relative() { return relative; }
    public String sha256() { return sha256; }
}
