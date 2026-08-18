package dev.eugene.publicationexporter.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivationMarkerTest {

    private static final String VALID_SHA256 =
            "a".repeat(64);

    @Test
    void isValidForACorrectlyShapedMarker() {
        ActivationMarker marker = new ActivationMarker(1, VALID_SHA256, Instant.parse("2026-08-18T00:00:00Z"));

        assertTrue(marker.isValid());
    }

    @Test
    void isInvalidForAnUnsupportedSchemaVersion() {
        ActivationMarker marker = new ActivationMarker(2, VALID_SHA256, Instant.parse("2026-08-18T00:00:00Z"));

        assertFalse(marker.isValid());
    }

    @Test
    void isInvalidForAMalformedInventoryHash() {
        ActivationMarker marker = new ActivationMarker(1, "not-a-sha256", Instant.parse("2026-08-18T00:00:00Z"));

        assertFalse(marker.isValid());
    }

    @Test
    void isInvalidForAnEmptyInventoryHash() {
        ActivationMarker marker = new ActivationMarker(1, "", Instant.parse("2026-08-18T00:00:00Z"));

        assertFalse(marker.isValid());
    }

    @Test
    void isInvalidForAnUppercaseInventoryHash() {
        ActivationMarker marker = new ActivationMarker(1, "A".repeat(64), Instant.parse("2026-08-18T00:00:00Z"));

        assertFalse(marker.isValid());
    }

    @Test
    void isInvalidForAHashWithTheWrongLength() {
        ActivationMarker marker = new ActivationMarker(1, "a".repeat(63), Instant.parse("2026-08-18T00:00:00Z"));

        assertFalse(marker.isValid());
    }

    @Test
    void isInvalidForSchemaVersionZero() {
        ActivationMarker marker = new ActivationMarker(0, VALID_SHA256, Instant.parse("2026-08-18T00:00:00Z"));

        assertFalse(marker.isValid());
    }

    @Test
    void isInvalidForANegativeSchemaVersion() {
        ActivationMarker marker = new ActivationMarker(-1, VALID_SHA256, Instant.parse("2026-08-18T00:00:00Z"));

        assertFalse(marker.isValid());
    }

    @Test
    void rejectsNullInventorySha256() {
        assertThrows(NullPointerException.class,
                () -> new ActivationMarker(1, null, Instant.parse("2026-08-18T00:00:00Z")));
    }

    @Test
    void rejectsNullActivatedAt() {
        assertThrows(NullPointerException.class, () -> new ActivationMarker(1, VALID_SHA256, null));
    }
}
