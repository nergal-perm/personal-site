package dev.eugene.publicationexporter.note;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Frontmatter {

    private static final String DELIMITER = "---";

    private final Map<String, FrontmatterScalar> frontmatterValues;
    private final String body;

    private Frontmatter(Map<String, FrontmatterScalar> frontmatterValues, String body) {
        this.frontmatterValues = Map.copyOf(frontmatterValues);
        this.body = Objects.requireNonNull(body, "body");
    }

    public static Frontmatter parse(String noteSource) {
        Objects.requireNonNull(noteSource, "noteSource");
        List<String> lines = noteSource.lines().toList();
        if (!startsWithFrontmatterDelimiter(lines)) {
            return new Frontmatter(Map.of(), noteSource);
        }
        ParsedHeader header = parseHeader(lines);
        if (header == null) {
            return new Frontmatter(Map.of(), noteSource);
        }
        return new Frontmatter(header.values(), bodyAfter(lines, header.closingDelimiterLineIndex()));
    }

    public Optional<String> string(String key) {
        return Optional.ofNullable(frontmatterValues.get(key))
                .flatMap(FrontmatterScalar::stringValue);
    }

    public boolean flag(String key) {
        return Optional.ofNullable(frontmatterValues.get(key))
                .map(FrontmatterScalar::isBareTrue)
                .orElse(false);
    }

    public String body() {
        return body;
    }

    @Override
    public String toString() {
        return "Frontmatter[frontmatterValues=" + frontmatterValues + ", body=" + body + "]";
    }

    private static boolean startsWithFrontmatterDelimiter(List<String> lines) {
        return !lines.isEmpty() && DELIMITER.equals(lines.get(0).strip());
    }

    private record ParsedHeader(Map<String, FrontmatterScalar> values, int closingDelimiterLineIndex) {
    }

    /**
     * Scans the same lines the original single-purpose loop scanned, but now also records where the
     * closing delimiter was found, so {@link #bodyAfter} can slice the same {@code lines} list without
     * a second, independent scan. Returns {@code null} exactly when the original loop would have
     * treated the block as absent: a malformed line before any closing delimiter is found, or no
     * closing delimiter at all.
     */
    private static ParsedHeader parseHeader(List<String> lines) {
        Map<String, FrontmatterScalar> values = new LinkedHashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (DELIMITER.equals(line.strip())) {
                return new ParsedHeader(values, index);
            }
            if (!addKeyValue(values, line)) {
                return null;
            }
        }
        return null;
    }

    private static String bodyAfter(List<String> lines, int closingDelimiterLineIndex) {
        return String.join("\n", lines.subList(closingDelimiterLineIndex + 1, lines.size()));
    }

    private static boolean addKeyValue(Map<String, FrontmatterScalar> values, String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String key = line.substring(0, colon).strip();
        if (key.isEmpty()) {
            return false;
        }
        Optional<FrontmatterScalar> value = FrontmatterScalar.parse(line.substring(colon + 1).strip());
        if (value.isEmpty() || values.containsKey(key)) {
            return false;
        }
        values.put(key, value.get());
        return true;
    }
}
