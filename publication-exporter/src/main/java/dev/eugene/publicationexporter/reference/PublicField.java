package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PublicField {

    private final String key;
    private final String value;

    private PublicField(String key, String value) {
        this.key = Objects.requireNonNull(key, "key");
        this.value = Objects.requireNonNull(value, "value");
    }

    public static PublicField of(String key, String value) {
        return new PublicField(key, value);
    }

    public static Optional<String> value(List<PublicField> fields, String key) {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(key, "key");
        return fields.stream()
                .filter(field -> field.key().equals(key))
                .map(PublicField::value)
                .findFirst();
    }

    @JsonProperty("key")
    public String key() {
        return key;
    }

    @JsonProperty("value")
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PublicField that)) {
            return false;
        }
        return key.equals(that.key) && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }

    @Override
    public String toString() {
        return "PublicField[key=" + key + ", value=" + value + "]";
    }
}
