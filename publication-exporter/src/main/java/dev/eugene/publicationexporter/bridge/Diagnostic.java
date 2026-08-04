package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public final class Diagnostic {

    private final String field;
    private final String message;
    private final boolean blocking;

    private Diagnostic(String field, String message, boolean blocking) {
        this.field = Objects.requireNonNull(field, "field");
        this.message = Objects.requireNonNull(message, "message");
        this.blocking = blocking;
    }

    public static Diagnostic blocking(String field, String message) {
        return new Diagnostic(field, message, true);
    }

    @JsonProperty("field")
    public String field() {
        return field;
    }

    @JsonProperty("message")
    public String message() {
        return message;
    }

    @JsonProperty("blocking")
    public boolean blocking() {
        return blocking;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Diagnostic that)) {
            return false;
        }
        return blocking == that.blocking && field.equals(that.field) && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, message, blocking);
    }

    @Override
    public String toString() {
        return "Diagnostic[field=" + field + ", message=" + message + ", blocking=" + blocking + "]";
    }
}
