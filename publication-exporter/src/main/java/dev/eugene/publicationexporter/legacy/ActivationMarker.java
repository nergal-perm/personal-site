package dev.eugene.publicationexporter.legacy;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record ActivationMarker(int schemaVersion, String inventorySha256, Instant activatedAt) {

    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public ActivationMarker {
        Objects.requireNonNull(inventorySha256, "inventorySha256");
        Objects.requireNonNull(activatedAt, "activatedAt");
    }

    public boolean isValid() {
        return schemaVersion == CURRENT_SCHEMA_VERSION && SHA256.matcher(inventorySha256).matches();
    }
}
