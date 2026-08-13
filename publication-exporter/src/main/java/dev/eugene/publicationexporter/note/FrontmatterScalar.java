package dev.eugene.publicationexporter.note;

import java.util.Optional;

final class FrontmatterScalar {

    private static final java.util.regex.Pattern YAML_NULL =
            java.util.regex.Pattern.compile("~|null|Null|NULL");
    private static final java.util.regex.Pattern YAML_BOOLEAN =
            java.util.regex.Pattern.compile("true|True|TRUE|false|False|FALSE");
    private static final java.util.regex.Pattern YAML_INTEGER =
            java.util.regex.Pattern.compile("[-+]?(?:[0-9][0-9_]*|0o[0-7_]+|0x[0-9a-fA-F_]+)");
    private static final java.util.regex.Pattern YAML_FLOAT =
            java.util.regex.Pattern.compile(
                    "[-+]?(?:(?:[0-9][0-9_]*)?\\.[0-9_]+(?:[eE][-+]?[0-9]+)?|[0-9][0-9_]*(?:[eE][-+]?[0-9]+)|\\.inf|\\.Inf|\\.INF|\\.nan|\\.NaN|\\.NAN)");
    private static final java.util.regex.Pattern YAML_DATE =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final java.util.regex.Pattern YAML_TIMESTAMP =
            java.util.regex.Pattern.compile(
                    "\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[-+]\\d{2}(?::\\d{2})?)?");

    private final String value;
    private final boolean quoted;

    private FrontmatterScalar(String value, boolean quoted) {
        this.value = value;
        this.quoted = quoted;
    }

    static Optional<FrontmatterScalar> parse(String token) {
        if (isQuoted(token)) {
            return Optional.of(new FrontmatterScalar(token.substring(1, token.length() - 1), true));
        }
        if (hasUnmatchedQuote(token) || token.contains(":") || isFlowCollection(token)) {
            return Optional.empty();
        }
        return Optional.of(new FrontmatterScalar(token, false));
    }

    boolean isBareTrue() {
        return !quoted && "true".equals(value);
    }

    Optional<Boolean> booleanValue() {
        if (quoted) {
            return Optional.empty();
        }
        if ("true".equalsIgnoreCase(value)) {
            return Optional.of(true);
        }
        if ("false".equalsIgnoreCase(value)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    Optional<String> stringValue() {
        if (!quoted && isYamlNullOrBoolean()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    Optional<String> listStringValue() {
        if (!quoted && isYamlNonStringScalar()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private static boolean isQuoted(String token) {
        return token.length() >= 2
                && ((token.startsWith("\"") && token.endsWith("\""))
                || (token.startsWith("'") && token.endsWith("'")));
    }

    private static boolean hasUnmatchedQuote(String token) {
        return token.startsWith("\"") || token.endsWith("\"")
                || token.startsWith("'") || token.endsWith("'");
    }

    private static boolean isFlowCollection(String token) {
        return (token.startsWith("[") && token.endsWith("]"))
                || (token.startsWith("{") && token.endsWith("}"));
    }

    private boolean isYamlNullOrBoolean() {
        return value.isEmpty()
                || YAML_NULL.matcher(value).matches()
                || YAML_BOOLEAN.matcher(value).matches();
    }

    private boolean isYamlNonStringScalar() {
        return isYamlNullOrBoolean()
                || YAML_INTEGER.matcher(value).matches()
                || YAML_FLOAT.matcher(value).matches()
                || YAML_DATE.matcher(value).matches()
                || YAML_TIMESTAMP.matcher(value).matches();
    }
}
