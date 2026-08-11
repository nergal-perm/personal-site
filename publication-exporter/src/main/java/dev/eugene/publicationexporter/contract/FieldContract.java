package dev.eugene.publicationexporter.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class FieldContract {

    public enum Type { BOOLEAN, STRING }

    private final String name;
    private final Type type;
    private final List<String> allowedValues;
    private final String pattern;
    private final boolean nonBlank;

    private FieldContract(String name, Type type, List<String> allowedValues, String pattern, boolean nonBlank) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.allowedValues = allowedValues == null ? null : List.copyOf(allowedValues);
        this.pattern = pattern;
        this.nonBlank = nonBlank;
    }

    public static FieldContract allowedValue(String name, Type type, String literalValue) {
        return new FieldContract(name, type, List.of(Objects.requireNonNull(literalValue, "literalValue")),
                null, false);
    }

    public static FieldContract matchingPattern(String name, String patternText) {
        return new FieldContract(name, Type.STRING, null, Objects.requireNonNull(patternText, "patternText"), false);
    }

    public static FieldContract nonBlank(String name) {
        return new FieldContract(name, Type.STRING, null, null, true);
    }

    @JsonProperty("name")
    public String name() {
        return name;
    }

    @JsonProperty("type")
    public Type type() {
        return type;
    }

    @JsonProperty("allowedValues")
    public List<String> allowedValues() {
        return allowedValues;
    }

    @JsonProperty("pattern")
    public String pattern() {
        return pattern;
    }

    @JsonProperty("nonBlank")
    public boolean nonBlank() {
        return nonBlank;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FieldContract that)) {
            return false;
        }
        return nonBlank == that.nonBlank && name.equals(that.name) && type == that.type
                && Objects.equals(allowedValues, that.allowedValues) && Objects.equals(pattern, that.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, allowedValues, pattern, nonBlank);
    }

    @Override
    public String toString() {
        return "FieldContract[name=" + name + ", type=" + type + ", allowedValues=" + allowedValues
                + ", pattern=" + pattern + ", nonBlank=" + nonBlank + "]";
    }
}
