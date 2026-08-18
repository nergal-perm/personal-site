package dev.eugene.publicationexporter.release;

import java.util.Objects;

public record OccurrenceResolution(String body, int activatedCount, int deactivatedCount) {

    public OccurrenceResolution {
        Objects.requireNonNull(body, "body");
    }

    public static OccurrenceResolution of(String body, int activatedCount, int deactivatedCount) {
        return new OccurrenceResolution(body, activatedCount, deactivatedCount);
    }
}
