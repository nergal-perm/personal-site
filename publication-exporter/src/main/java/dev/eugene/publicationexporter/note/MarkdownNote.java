package dev.eugene.publicationexporter.note;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class MarkdownNote {

    private static final String DELIMITER = "---";

    private final Map<String, FrontmatterScalar> frontmatterValues;
    private final Map<String, List<Map<String, String>>> frontmatterMapLists;
    private final String body;
    private final String originalSource;
    private final HeaderState headerState;

    private MarkdownNote(
            Map<String, FrontmatterScalar> frontmatterValues,
            Map<String, List<Map<String, String>>> frontmatterMapLists,
            String body, String originalSource, HeaderState headerState) {
        this.frontmatterValues = Map.copyOf(frontmatterValues);
        this.frontmatterMapLists = immutableMapLists(frontmatterMapLists);
        this.body = Objects.requireNonNull(body, "body");
        this.originalSource = Objects.requireNonNull(originalSource, "originalSource");
        this.headerState = Objects.requireNonNull(headerState, "headerState");
    }

    public static MarkdownNote parse(String noteSource) {
        Objects.requireNonNull(noteSource, "noteSource");
        List<String> lines = noteSource.lines().toList();
        if (!startsWithFrontmatterDelimiter(lines)) {
            return new MarkdownNote(Map.of(), Map.of(), noteSource, noteSource, HeaderState.ABSENT);
        }
        Optional<ParsedHeader> header = parseHeader(lines);
        if (header.isEmpty()) {
            return new MarkdownNote(Map.of(), Map.of(), noteSource, noteSource, HeaderState.MALFORMED);
        }
        ParsedHeader parsed = header.get();
        return new MarkdownNote(parsed.values(), parsed.mapLists(),
                bodyAfter(noteSource, parsed.closingDelimiterLineIndex()), noteSource, HeaderState.PRESENT);
    }

    public String sourceWithScalar(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        List<String> lines = originalSource.lines().toList();
        int closingIndex = closingDelimiterLineIndex(lines);
        String newLineText = key + ": " + value;
        int existingIndex = existingKeyLineIndex(lines, key, closingIndex);
        return existingIndex >= 0
                ? spliceValueReplace(lines, existingIndex, value)
                : spliceInsertBefore(lines, closingIndex, newLineText);
    }

    private String spliceValueReplace(List<String> lines, int lineIndex, String newValue) {
        String line = lines.get(lineIndex);
        int valueStartInLine = valueStartOffset(line);
        int valueEndInLine = valueEndOffset(line, valueStartInLine);
        int lineStart = lineStartOffset(lineIndex);
        int valueStart = lineStart + valueStartInLine;
        int valueEnd = lineStart + valueEndInLine;
        return originalSource.substring(0, valueStart) + newValue + originalSource.substring(valueEnd);
    }

    private static int valueStartOffset(String line) {
        int offset = line.indexOf(':') + 1;
        while (offset < line.length() && Character.isWhitespace(line.charAt(offset))) {
            offset++;
        }
        return offset;
    }

    private static int valueEndOffset(String line, int valueStart) {
        int end = inlineCommentStart(line, valueStart);
        while (end > valueStart && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private static int inlineCommentStart(String line, int valueStart) {
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean escaped = false;
        for (int index = valueStart; index < line.length(); index++) {
            char current = line.charAt(index);
            if (inDoubleQuotes && current == '\\' && !escaped) {
                escaped = true;
                continue;
            }
            if (current == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (current == '"' && !inSingleQuotes && !escaped) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (current == '#' && !inSingleQuotes && !inDoubleQuotes
                    && index > valueStart && Character.isWhitespace(line.charAt(index - 1))) {
                return index;
            }
            escaped = false;
        }
        return line.length();
    }

    private String spliceInsertBefore(List<String> lines, int lineIndex, String newLineText) {
        int insertionPoint = lineStartOffset(lineIndex);
        String terminator = terminatorBefore(lines, lineIndex);
        return originalSource.substring(0, insertionPoint) + newLineText + terminator
                + originalSource.substring(insertionPoint);
    }

    private static int closingDelimiterLineIndex(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).strip().equals(DELIMITER)) {
            throw new IllegalStateException("sourceWithScalar requires a note with frontmatter already present.");
        }
        return parseHeader(lines)
                .map(ParsedHeader::closingDelimiterLineIndex)
                .orElseThrow(() -> new IllegalStateException(
                        "sourceWithScalar requires a note with frontmatter already present."));
    }

    private int lineStartOffset(int lineIndex) {
        return lineIndex == 0 ? 0 : offsetAfterLineTerminator(originalSource, lineIndex - 1);
    }

    private String terminatorBefore(List<String> lines, int lineIndex) {
        int previousLineEnd = lineStartOffset(lineIndex - 1) + lines.get(lineIndex - 1).length();
        int thisLineStart = lineStartOffset(lineIndex);
        return originalSource.substring(previousLineEnd, thisLineStart);
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

    public List<Map<String, String>> listOfMaps(String key) {
        Objects.requireNonNull(key, "key");
        return frontmatterMapLists.getOrDefault(key, List.of());
    }

    public boolean flag(String key) {
        return Optional.ofNullable(frontmatterValues.get(key))
                .map(FrontmatterScalar::isBareTrue)
                .orElse(false);
    }

    public String body() {
        return body;
    }

    public HeaderState headerState() {
        return headerState;
    }

    private static boolean startsWithFrontmatterDelimiter(List<String> lines) {
        return !lines.isEmpty() && DELIMITER.equals(lines.get(0).strip());
    }

    private record ParsedHeader(
            Map<String, FrontmatterScalar> values,
            Map<String, List<Map<String, String>>> mapLists,
            int closingDelimiterLineIndex) {
        private ParsedHeader {
        }

        private static ParsedHeader of(
                Map<String, FrontmatterScalar> values,
                Map<String, List<Map<String, String>>> mapLists,
                int closingDelimiterLineIndex) {
            return new ParsedHeader(values, mapLists, closingDelimiterLineIndex);
        }
    }

    private static Optional<ParsedHeader> parseHeader(List<String> lines) {
        Map<String, FrontmatterScalar> values = new LinkedHashMap<>();
        Map<String, List<Map<String, String>>> mapLists = new LinkedHashMap<>();
        int index = 1;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (DELIMITER.equals(line.strip())) {
                return Optional.of(ParsedHeader.of(values, mapLists, index));
            }
            Optional<FrontmatterLine> parsedLine = FrontmatterLine.parse(line);
            if (parsedLine.isEmpty() || containsKey(values, mapLists, parsedLine.get().key())) {
                return Optional.empty();
            }
            FrontmatterLine frontmatterLine = parsedLine.get();
            if (frontmatterLine.emptyList()) {
                mapLists.put(frontmatterLine.key(), List.of());
                index++;
                continue;
            }
            if (frontmatterLine.startsMapList(lines, index + 1)) {
                Optional<ParsedMapList> parsedMapList = parseMapList(lines, index + 1);
                if (parsedMapList.isEmpty()) {
                    return Optional.empty();
                }
                mapLists.put(frontmatterLine.key(), parsedMapList.get().entries());
                index = parsedMapList.get().nextLineIndex();
                continue;
            }
            Optional<FrontmatterScalar> value = FrontmatterScalar.parse(frontmatterLine.token());
            if (value.isEmpty()) {
                return Optional.empty();
            }
            values.put(frontmatterLine.key(), value.get());
            index++;
        }
        return Optional.empty();
    }

    private static Optional<ParsedMapList> parseMapList(List<String> lines, int firstLineIndex) {
        List<Map<String, String>> entries = new ArrayList<>();
        Map<String, String> currentEntry = null;
        int itemIndent = -1;
        int index = firstLineIndex;
        while (index < lines.size() && indentation(lines.get(index)) > 0) {
            String line = lines.get(index);
            int indent = indentation(line);
            String content = line.substring(indent);
            if (content.startsWith("- ")) {
                if (currentEntry != null) {
                    entries.add(immutableMap(currentEntry));
                }
                currentEntry = new LinkedHashMap<>();
                itemIndent = indent;
                if (!addMapEntry(currentEntry, content.substring(2))) {
                    return Optional.empty();
                }
            } else if (currentEntry == null || indent <= itemIndent || !addMapEntry(currentEntry, content)) {
                return Optional.empty();
            }
            index++;
        }
        if (currentEntry == null) {
            return Optional.empty();
        }
        entries.add(immutableMap(currentEntry));
        return Optional.of(new ParsedMapList(List.copyOf(entries), index));
    }

    private static boolean addMapEntry(Map<String, String> entry, String line) {
        Optional<FrontmatterLine> parsed = FrontmatterLine.parseContent(line);
        if (parsed.isEmpty() || parsed.get().token().isEmpty() || entry.containsKey(parsed.get().key())) {
            return false;
        }
        Optional<String> value = FrontmatterScalar.parse(parsed.get().token())
                .flatMap(FrontmatterScalar::stringValue);
        if (value.isEmpty()) {
            return false;
        }
        entry.put(parsed.get().key(), value.get());
        return true;
    }

    private static int indentation(String line) {
        int indentation = 0;
        while (indentation < line.length() && line.charAt(indentation) == ' ') {
            indentation++;
        }
        return indentation;
    }

    private static boolean containsKey(
            Map<String, FrontmatterScalar> values,
            Map<String, List<Map<String, String>>> mapLists,
            String key) {
        return values.containsKey(key) || mapLists.containsKey(key);
    }

    private static Map<String, List<Map<String, String>>> immutableMapLists(
            Map<String, List<Map<String, String>>> mapLists) {
        Map<String, List<Map<String, String>>> copied = new LinkedHashMap<>();
        mapLists.forEach((key, entries) -> copied.put(
                key, entries.stream().map(MarkdownNote::immutableMap).toList()));
        return Collections.unmodifiableMap(copied);
    }

    private static Map<String, String> immutableMap(Map<String, String> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
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

    private record FrontmatterLine(String key, String token) {

        private static Optional<FrontmatterLine> parse(String line) {
            return indentation(line) == 0 ? parseContent(line) : Optional.empty();
        }

        private static Optional<FrontmatterLine> parseContent(String line) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                return Optional.empty();
            }
            String key = line.substring(0, colon).strip();
            if (key.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new FrontmatterLine(key, line.substring(colon + 1).strip()));
        }

        private boolean emptyList() {
            return "[]".equals(token);
        }

        private boolean startsMapList(List<String> lines, int nextLineIndex) {
            return token.isEmpty()
                    && nextLineIndex < lines.size()
                    && indentation(lines.get(nextLineIndex)) > 0
                    && lines.get(nextLineIndex).stripLeading().startsWith("- ");
        }
    }

    private record ParsedMapList(List<Map<String, String>> entries, int nextLineIndex) {
    }

    public enum HeaderState {
        PRESENT,
        ABSENT,
        MALFORMED
    }
}
