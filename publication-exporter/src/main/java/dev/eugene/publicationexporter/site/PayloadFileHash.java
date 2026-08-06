package dev.eugene.publicationexporter.site;

import java.util.Objects;

public final class PayloadFileHash {
    private final String path;
    private final String sha256;

    private PayloadFileHash(String path, String sha256) {
        this.path = Objects.requireNonNull(path, "path");
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
    }

    public static PayloadFileHash of(String path, String sha256) {
        return new PayloadFileHash(path, sha256);
    }

    public String path() { return path; }
    public String sha256() { return sha256; }
}
