package dev.eugene.publicationexporter.legacy;

import java.util.Objects;
import java.util.Optional;

final class NullActivationMarkerStore implements ActivationMarkerStore {

    private final Optional<ActivationMarker> marker;

    NullActivationMarkerStore(Optional<ActivationMarker> marker) {
        this.marker = Objects.requireNonNull(marker, "marker");
    }

    @Override
    public Optional<ActivationMarker> read() {
        return marker;
    }
}
