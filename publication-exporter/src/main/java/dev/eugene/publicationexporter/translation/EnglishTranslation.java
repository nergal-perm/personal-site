package dev.eugene.publicationexporter.translation;

import java.util.Objects;

public final class EnglishTranslation {

    private final String body;
    private final String title;
    private final String description;

    private EnglishTranslation(String body, String title, String description) {
        this.body = Objects.requireNonNull(body, "body");
        this.title = Objects.requireNonNull(title, "title");
        this.description = Objects.requireNonNull(description, "description");
    }

    public static EnglishTranslation of(String body, String title, String description) {
        return new EnglishTranslation(body, title, description);
    }

    public String body() {
        return body;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }
}
