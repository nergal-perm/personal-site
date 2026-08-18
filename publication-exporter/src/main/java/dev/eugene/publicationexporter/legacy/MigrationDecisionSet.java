package dev.eugene.publicationexporter.legacy;

import java.util.Objects;
import java.util.regex.Pattern;

public record MigrationDecisionSet(int schemaVersion, String inventorySha256) {

    private static final Pattern LOWERCASE_HEX_SHA256 = Pattern.compile("[0-9a-f]{64}");

    public MigrationDecisionSet {
        Objects.requireNonNull(inventorySha256, "inventorySha256");
        if (schemaVersion != 1 || !LOWERCASE_HEX_SHA256.matcher(inventorySha256).matches()) {
            throw new LegacyMigrationDecisionException(
                    "Decision file has an invalid schema or inventory fingerprint.");
        }
    }
}
