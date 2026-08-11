package dev.eugene.publicationexporter.translation;

import dev.eugene.publicationexporter.reference.PublicField;

import java.util.List;
import java.util.Objects;

public final class EnglishTranslation {

    private final String body;
    private final List<PublicField> fields;

    private EnglishTranslation(String body, List<PublicField> fields) {
        this.body = Objects.requireNonNull(body, "body");
        this.fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }

    public static EnglishTranslation of(String body, List<PublicField> fields) {
        return new EnglishTranslation(body, fields);
    }

    public String body() {
        return body;
    }

    public List<PublicField> fields() {
        return fields;
    }
}
