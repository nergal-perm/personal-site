package dev.eugene.publicationexporter.legacy;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public interface ActivationMarkerStore {

    enum Status {
        ABSENT,
        INVALID_PRESENT,
        PARSED
    }

    record Inspection(Status status, Optional<ActivationMarker> marker) {

        public Inspection {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(marker, "marker");
            if ((status == Status.PARSED && marker.isEmpty())
                    || (status != Status.PARSED && marker.isPresent())) {
                throw new IllegalArgumentException("Marker inspection state is inconsistent.");
            }
        }

        static Inspection absent() {
            return new Inspection(Status.ABSENT, Optional.empty());
        }

        static Inspection invalidPresent() {
            return new Inspection(Status.INVALID_PRESENT, Optional.empty());
        }

        static Inspection parsed(ActivationMarker marker) {
            return new Inspection(Status.PARSED, Optional.of(marker));
        }
    }

    default void preflight() {
    }

    default boolean exists() {
        return read().isPresent();
    }

    Optional<ActivationMarker> read();

    default Inspection inspect() {
        return read().map(Inspection::parsed).orElseGet(Inspection::absent);
    }

    void save(ActivationMarker marker);

    void clear();

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
