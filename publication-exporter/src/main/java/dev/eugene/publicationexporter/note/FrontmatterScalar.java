package dev.eugene.publicationexporter.note;

import java.util.Optional;

final class FrontmatterScalar {

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
        if (hasUnmatchedQuote(token) || token.contains(":")) {
            return Optional.empty();
        }
        return Optional.of(new FrontmatterScalar(token, false));
    }

    boolean isBareTrue() {
        return !quoted && "true".equals(value);
    }

    Optional<String> stringValue() {
        if (!quoted && isNullOrBoolean()) {
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

    private boolean isNullOrBoolean() {
        return value.isEmpty() || value.equals("null") || value.equals("~")
                || value.equals("true") || value.equals("false");
    }
}
