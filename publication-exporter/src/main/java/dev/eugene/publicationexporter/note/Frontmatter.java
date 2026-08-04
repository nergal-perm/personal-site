package dev.eugene.publicationexporter.note;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Frontmatter {

    private static final String DELIMITER = "---";

    private final Map<String, String> frontmatterValues;

    private Frontmatter(Map<String, String> frontmatterValues) {
        this.frontmatterValues = Map.copyOf(frontmatterValues);
    }

    public static Frontmatter parse(String noteSource) {
        Objects.requireNonNull(noteSource, "noteSource");
        List<String> lines = noteSource.lines().toList();
        if (!startsWithFrontmatterDelimiter(lines)) {
            return new Frontmatter(Map.of());
        }
        return new Frontmatter(parseKeyValueLines(lines));
    }

    public Optional<String> string(String key) {
        return Optional.ofNullable(frontmatterValues.get(key));
    }

    public boolean flag(String key) {
        return "true".equals(frontmatterValues.get(key));
    }

    @Override
    public String toString() {
        return "Frontmatter[frontmatterValues=" + frontmatterValues + "]";
    }

    private static boolean startsWithFrontmatterDelimiter(List<String> lines) {
        return !lines.isEmpty() && DELIMITER.equals(lines.get(0).strip());
    }

    private static Map<String, String> parseKeyValueLines(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            if (DELIMITER.equals(line.strip())) {
                break;
            }
            addKeyValueIfPresent(values, line);
        }
        return values;
    }

    private static void addKeyValueIfPresent(Map<String, String> values, String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return;
        }
        String key = line.substring(0, colon).strip();
        if (key.isEmpty()) {
            return;
        }
        values.put(key, unquote(line.substring(colon + 1).strip()));
    }

    private static String unquote(String value) {
        boolean doubleQuoted = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"");
        boolean singleQuoted = value.length() >= 2 && value.startsWith("'") && value.endsWith("'");
        return (doubleQuoted || singleQuoted) ? value.substring(1, value.length() - 1) : value;
    }
}
