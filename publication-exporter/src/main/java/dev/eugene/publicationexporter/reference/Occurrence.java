package dev.eugene.publicationexporter.reference;

import java.util.Objects;

public record Occurrence(String id, int order, String targetSourceId, String ruLabel, String enLabel) {

    public Occurrence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(targetSourceId, "targetSourceId");
        Objects.requireNonNull(ruLabel, "ruLabel");
        Objects.requireNonNull(enLabel, "enLabel");
    }
}
