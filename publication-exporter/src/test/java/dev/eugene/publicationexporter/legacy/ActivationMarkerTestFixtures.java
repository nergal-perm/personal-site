package dev.eugene.publicationexporter.legacy;

import java.time.Instant;

public final class ActivationMarkerTestFixtures {

    private ActivationMarkerTestFixtures() {
    }

    public static ActivationMarkerStore activatedMarkerStore() {
        return ActivationMarkerStore.createNull(
                new ActivationMarker(1, "a".repeat(64), Instant.parse("2026-08-18T00:00:00Z")));
    }
}
