package dev.eugene.publicationexporter.note;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class Frontmatter {

    private static final String DELIMITER = "---";

    private final Map<String, FrontmatterScalar> frontmatterValues;
    private final String body;
    private final String originalSource;

    private Frontmatter(Map<String, FrontmatterScalar> frontmatterValues, String body, String originalSource) {
        this.frontmatterValues = Map.copyOf(frontmatterValues);
        this.body = Objects.requireNonNull(body, "body");
        this.originalSource = Objects.requireNonNull(originalSource, "originalSource");
    }

    public static Frontmatter parse(String noteSource) {
        Objects.requireNonNull(noteSource, "noteSource");
        List<String> lines = noteSource.lines().toList();
        if (!startsWithFrontmatterDelimiter(lines)) {
            return new Frontmatter(Map.of(), noteSource, noteSource);
        }
        ParsedHeader header = parseHeader(lines);
        if (header == null) {
            return new Frontmatter(Map.of(), noteSource, noteSource);
        }
        return new Frontmatter(header.values(),
                bodyAfter(noteSource, header.closingDelimiterLineIndex()), noteSource);
    }

    public String withScalarSet(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        List<String> lines = new ArrayList<>(originalSource.lines().toList());
        int closingIndex = closingDelimiterLineIndex(lines);
        String newLine = key + ": " + value;
        int existingIndex = existingKeyLineIndex(lines, key, closingIndex);
        if (existingIndex >= 0) {
            lines.set(existingIndex, newLine);
        } else {
            lines.add(closingIndex, newLine);
        }
        String lineEnding = originalSource.contains("\r\n") ? "\r\n" : "\n";
        String rebuilt = String.join(lineEnding, lines);
        boolean sourceEndsWithNewline = originalSource.endsWith("\n");
        return sourceEndsWithNewline ? rebuilt + lineEnding : rebuilt;
    }

    private static int closingDelimiterLineIndex(List<String> lines) {
        for (int index = 1; index < lines.size(); index++) {
            if (DELIMITER.equals(lines.get(index).strip())) {
                return index;
            }
        }
        throw new IllegalStateException("withScalarSet requires a note with frontmatter already present.");
    }

    private static int existingKeyLineIndex(List<String> lines, String key, int closingIndex) {
        for (int index = 1; index < closingIndex; index++) {
            int colon = lines.get(index).indexOf(':');
            if (colon >= 0 && lines.get(index).substring(0, colon).strip().equals(key)) {
                return index;
            }
        }
        return -1;
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
        private ParsedHeader {
        }

        private static ParsedHeader of(Map<String, FrontmatterScalar> values, int closingDelimiterLineIndex) {
            return new ParsedHeader(values, closingDelimiterLineIndex);
        }
    }

    /**
     * Scans the same lines the original single-purpose loop scanned, but now also records where the
     * closing delimiter was found, so {@link #bodyAfter} can slice the original source at the matching
     * character offset. Returns {@code null} exactly when the original loop would have
     * treated the block as absent: a malformed line before any closing delimiter is found, or no
     * closing delimiter at all.
     */
    private static ParsedHeader parseHeader(List<String> lines) {
        Map<String, FrontmatterScalar> values = new LinkedHashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (DELIMITER.equals(line.strip())) {
                return ParsedHeader.of(values, index);
            }
            if (!addKeyValue(values, line)) {
                return null;
            }
        }
        return null;
    }

    private static String bodyAfter(String noteSource, int closingDelimiterLineIndex) {
        return noteSource.substring(offsetAfterLineTerminator(noteSource, closingDelimiterLineIndex));
    }

    private static int offsetAfterLineTerminator(String source, int lineIndex) {
        int offset = 0;
        for (int currentLine = 0; currentLine <= lineIndex; currentLine++) {
            while (offset < source.length()
                    && source.charAt(offset) != '\n'
                    && source.charAt(offset) != '\r') {
                offset++;
            }
            if (offset == source.length()) {
                return offset;
            }
            if (source.charAt(offset) == '\r'
                    && offset + 1 < source.length()
                    && source.charAt(offset + 1) == '\n') {
                offset += 2;
            } else {
                offset++;
            }
        }
        return offset;
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
