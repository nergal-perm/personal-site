package dev.eugene.publicationexporter.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NullActivationMarkerStoreTest {

    @Test
    void bareCreateNullHasNoMarker() {
        ActivationMarkerStore store = ActivationMarkerStore.createNull();

        assertEquals(Optional.empty(), store.read());
    }

    @Test
    void createNullWithAPresetReturnsIt() {
        ActivationMarker preset = new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z"));
        ActivationMarkerStore store = ActivationMarkerStore.createNull(preset);

        assertEquals(Optional.of(preset), store.read());
    }
}
