package dev.eugene.publicationexporter.admission;

import java.util.Objects;
import java.util.regex.Pattern;

public final class FieldRule {

    public enum Kind { MUST_EQUAL, MUST_MATCH, NON_BLANK }

    private final String field;
    private final Kind kind;
    private final String literalValue;
    private final Pattern pattern;
    private final String patternDescription;

    private FieldRule(String field, Kind kind, String literalValue, Pattern pattern, String patternDescription) {
        this.field = Objects.requireNonNull(field, "field");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.literalValue = literalValue;
        this.pattern = pattern;
        this.patternDescription = patternDescription;
    }

    public static FieldRule mustEqual(String field, String literalValue) {
        return new FieldRule(field, Kind.MUST_EQUAL, Objects.requireNonNull(literalValue, "literalValue"),
                null, null);
    }

    public static FieldRule mustMatch(String field, Pattern pattern, String patternDescription) {
        return new FieldRule(field, Kind.MUST_MATCH, null,
                Objects.requireNonNull(pattern, "pattern"),
                Objects.requireNonNull(patternDescription, "patternDescription"));
    }

    public static FieldRule nonBlank(String field) {
        return new FieldRule(field, Kind.NON_BLANK, null, null, null);
    }

    public String field() {
        return field;
    }

    public Kind kind() {
        return kind;
    }

    public String literalValue() {
        return literalValue;
    }

    public Pattern pattern() {
        return pattern;
    }

    public String patternDescription() {
        return patternDescription;
    }

    @Override
    public String toString() {
        return "FieldRule[field=" + field + ", kind=" + kind + ", literalValue=" + literalValue
                + ", pattern=" + pattern + ", patternDescription=" + patternDescription + "]";
    }
}
