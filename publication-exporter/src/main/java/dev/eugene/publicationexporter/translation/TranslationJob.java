package dev.eugene.publicationexporter.translation;

import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.PublicField;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class TranslationJob {

    private final String id;
    private final String sourceFingerprint;

    private TranslationJob(String id, String sourceFingerprint) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceFingerprint = Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
    }

    public static TranslationJob forSource(String ruBody, List<PublicField> ruFields) {
        List<PublicField> sourceFields = List.copyOf(Objects.requireNonNull(ruFields, "ruFields"));
        requireSourceFields(ruBody, sourceFields);
        String fingerprint = fingerprintFor(ruBody, sourceFields);
        return new TranslationJob(UUID.randomUUID().toString(), fingerprint);
    }

    private static void requireSourceFields(String ruBody, List<PublicField> ruFields) {
        Objects.requireNonNull(ruBody, "ruBody");
        for (PublicField field : ruFields) {
            Objects.requireNonNull(field.value(), "field.value");
        }
    }

    private static String fingerprintFor(String ruBody, List<PublicField> ruFields) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(ruBody.length()).append(':').append(ruBody);
        for (PublicField field : ruFields) {
            canonical.append(field.value().length()).append(':').append(field.value());
        }
        return ContentHash.sha256Hex(canonical.toString());
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
