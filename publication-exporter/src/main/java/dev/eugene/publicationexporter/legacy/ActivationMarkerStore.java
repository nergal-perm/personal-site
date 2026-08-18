package dev.eugene.publicationexporter.legacy;

import java.nio.file.Path;
import java.util.Optional;

public interface ActivationMarkerStore {

    Optional<ActivationMarker> read();

    static ActivationMarkerStore create(Path reviewRoot) {
        return new FilesystemActivationMarkerStore(reviewRoot);
    }

    static ActivationMarkerStore createNull() {
        return new NullActivationMarkerStore(Optional.empty());
    }

    static ActivationMarkerStore createNull(ActivationMarker preset) {
        return new NullActivationMarkerStore(Optional.of(preset));
    }
}
