package dev.eugene.publicationexporter.translation;

import dev.eugene.publicationexporter.hash.ContentHash;

import java.util.Objects;
import java.util.UUID;

public final class TranslationJob {

    private final String id;
    private final String sourceFingerprint;

    private TranslationJob(String id, String sourceFingerprint) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceFingerprint = Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
    }

    public static TranslationJob forSource(String ruBody, String ruTitle, String ruDescription) {
        Objects.requireNonNull(ruBody, "ruBody");
        Objects.requireNonNull(ruTitle, "ruTitle");
        Objects.requireNonNull(ruDescription, "ruDescription");
        String fingerprint = ContentHash.sha256Hex(ruBody + "\u0000" + ruTitle + "\u0000" + ruDescription);
        return new TranslationJob(UUID.randomUUID().toString(), fingerprint);
    }

    public String id() {
        return id;
    }

    public String sourceFingerprint() {
        return sourceFingerprint;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TranslationJob that)) {
            return false;
        }
        return id.equals(that.id) && sourceFingerprint.equals(that.sourceFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sourceFingerprint);
    }

    @Override
    public String toString() {
        return "TranslationJob[id=" + id + ", sourceFingerprint=" + sourceFingerprint + "]";
    }
}
