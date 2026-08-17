package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.Optional;

record LinkOccurrence(String targetStem, String label, Optional<String> route, int spanStart, int spanEnd) {

    LinkOccurrence {
        Objects.requireNonNull(targetStem, "targetStem");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(route, "route");
    }
}
