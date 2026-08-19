package dev.eugene.publicationexporter.legacy;

import java.util.Objects;
import java.util.Optional;

final class NullActivationMarkerStore implements ActivationMarkerStore {

    private Optional<ActivationMarker> marker;

    NullActivationMarkerStore(Optional<ActivationMarker> marker) {
        this.marker = Objects.requireNonNull(marker, "marker");
    }

    @Override
    public Optional<ActivationMarker> read() {
        return marker;
    }

    @Override
    public void save(ActivationMarker marker) {
        this.marker = Optional.of(Objects.requireNonNull(marker, "marker"));
    }

    @Override
    public void clear() {
        marker = Optional.empty();
    }
}
