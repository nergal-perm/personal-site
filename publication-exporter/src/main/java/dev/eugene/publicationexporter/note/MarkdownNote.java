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
    private final Map<String, FrontmatterList> frontmatterStructuredValues;
    private final String body;
    private final String originalSource;
    private final HeaderState headerState;

    private MarkdownNote(
            Map<String, FrontmatterScalar> frontmatterValues,
            Map<String, FrontmatterList> frontmatterStructuredValues,
            String body, String originalSource, HeaderState headerState) {
        this.frontmatterValues = Map.copyOf(frontmatterValues);
        this.frontmatterStructuredValues = immutableStructuredValues(frontmatterStructuredValues);
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
        return new MarkdownNote(parsed.values(), parsed.structuredValues(),
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
        return Optional.ofNullable(frontmatterStructuredValues.get(key))
                .filter(FrontmatterList::containsOnlyScalarMaps)
                .map(FrontmatterList::entries)
                .orElseGet(List::of);
    }

    public List<String> listOfScalars(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(frontmatterStructuredValues.get(key))
                .filter(FrontmatterList::containsOnlyScalars)
                .map(FrontmatterList::scalarEntries)
                .orElseGet(List::of);
    }

    public Optional<String> opaqueListYaml(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(frontmatterStructuredValues.get(key))
                .filter(FrontmatterList::populated)
                .map(list -> list.yamlFor(key));
    }

    public StructuredField structuredField(String key) {
        Objects.requireNonNull(key, "key");
        if (frontmatterStructuredValues.containsKey(key)) {
            FrontmatterList field = frontmatterStructuredValues.get(key);
            if (!field.listShape()) {
                return StructuredField.NON_LIST;
            }
            return field.populated() ? StructuredField.POPULATED_LIST : StructuredField.EMPTY_LIST;
        }
        return frontmatterValues.containsKey(key)
                ? StructuredField.NON_LIST
                : StructuredField.ABSENT;
    }

    public boolean hasOnlyScalarMapEntries(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(frontmatterStructuredValues.get(key))
                .filter(FrontmatterList::listShape)
                .map(FrontmatterList::containsOnlyScalarMaps)
                .orElse(false);
    }

    public boolean flag(String key) {
        return Optional.ofNullable(frontmatterValues.get(key))
                .map(FrontmatterScalar::isBareTrue)
                .orElse(false);
    }

    public Optional<Boolean> booleanValue(String key) {
        return Optional.ofNullable(frontmatterValues.get(key))
                .flatMap(FrontmatterScalar::booleanValue);
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
            Map<String, FrontmatterList> structuredValues,
            int closingDelimiterLineIndex) {
        private ParsedHeader {
        }

        private static ParsedHeader of(
                Map<String, FrontmatterScalar> values,
                Map<String, FrontmatterList> structuredValues,
                int closingDelimiterLineIndex) {
            return new ParsedHeader(values, structuredValues, closingDelimiterLineIndex);
        }
    }

    private static Optional<ParsedHeader> parseHeader(List<String> lines) {
        Map<String, FrontmatterScalar> values = new LinkedHashMap<>();
        Map<String, FrontmatterList> structuredValues = new LinkedHashMap<>();
        int index = 1;
        while (index < lines.size()) {
            if (isClosingDelimiter(lines, index)) {
                return Optional.of(ParsedHeader.of(values, structuredValues, index));
            }
            Optional<Integer> nextLineIndex = parseHeaderField(lines, index, values, structuredValues);
            if (nextLineIndex.isEmpty()) {
                return Optional.empty();
            }
            index = nextLineIndex.get();
        }
        return Optional.empty();
    }

    private static boolean isClosingDelimiter(List<String> lines, int lineIndex) {
        return DELIMITER.equals(lines.get(lineIndex).strip());
    }

    private static Optional<Integer> parseHeaderField(
            List<String> lines,
            int lineIndex,
            Map<String, FrontmatterScalar> values,
            Map<String, FrontmatterList> structuredValues) {
        Optional<FrontmatterLine> parsedLine = FrontmatterLine.parse(lines.get(lineIndex));
        if (parsedLine.isEmpty() || containsKey(values, structuredValues, parsedLine.get().key())) {
            return Optional.empty();
        }
        return recordHeaderField(lines, lineIndex, parsedLine.get(), values, structuredValues);
    }

    private static Optional<Integer> recordHeaderField(
            List<String> lines,
            int lineIndex,
            FrontmatterLine frontmatterLine,
            Map<String, FrontmatterScalar> values,
            Map<String, FrontmatterList> structuredValues) {
        if (frontmatterLine.emptyList()) {
            return recordEmptyList(frontmatterLine, lineIndex, structuredValues);
        }
        if (frontmatterLine.startsStructuredValue(lines, lineIndex + 1)) {
            return recordStructuredValue(lines, lineIndex, frontmatterLine, structuredValues);
        }
        if (frontmatterLine.inlineMapping()) {
            return recordInlineMapping(frontmatterLine, lineIndex, structuredValues);
        }
        return recordScalar(frontmatterLine, lineIndex, values);
    }

    private static Optional<Integer> recordEmptyList(
            FrontmatterLine frontmatterLine,
            int lineIndex,
            Map<String, FrontmatterList> structuredValues) {
        structuredValues.put(frontmatterLine.key(), FrontmatterList.empty());
        return Optional.of(lineIndex + 1);
    }

    private static Optional<Integer> recordStructuredValue(
            List<String> lines,
            int lineIndex,
            FrontmatterLine frontmatterLine,
            Map<String, FrontmatterList> structuredValues) {
        Optional<ParsedMapList> parsedMapList = parseMapList(lines, lineIndex + 1);
        parsedMapList.ifPresent(
                parsed -> structuredValues.put(frontmatterLine.key(), parsed.frontmatterList()));
        return parsedMapList.map(ParsedMapList::nextLineIndex);
    }

    private static Optional<Integer> recordInlineMapping(
            FrontmatterLine frontmatterLine,
            int lineIndex,
            Map<String, FrontmatterList> structuredValues) {
        structuredValues.put(frontmatterLine.key(), FrontmatterList.nonList(List.of()));
        return Optional.of(lineIndex + 1);
    }

    private static Optional<Integer> recordScalar(
            FrontmatterLine frontmatterLine,
            int lineIndex,
            Map<String, FrontmatterScalar> values) {
        Optional<FrontmatterScalar> value = FrontmatterScalar.parse(frontmatterLine.token());
        value.ifPresent(parsed -> values.put(frontmatterLine.key(), parsed));
        return value.map(ignored -> lineIndex + 1);
    }

    private static Optional<ParsedMapList> parseMapList(List<String> lines, int firstLineIndex) {
        int nextLineIndex = nextTopLevelLineIndex(lines, firstLineIndex);
        List<String> sourceLines = structuredValueLines(lines, firstLineIndex, nextLineIndex);
        return parseFrontmatterList(sourceLines)
                .map(frontmatterList -> ParsedMapList.of(frontmatterList, nextLineIndex));
    }

    private static int nextTopLevelLineIndex(List<String> lines, int firstLineIndex) {
        int index = firstLineIndex;
        while (index < lines.size() && indentation(lines.get(index)) > 0) {
            index++;
        }
        return index;
    }

    private static List<String> structuredValueLines(
            List<String> lines, int firstLineIndex, int nextLineIndex) {
        return List.copyOf(lines.subList(firstLineIndex, nextLineIndex));
    }

    private static Optional<FrontmatterList> parseFrontmatterList(List<String> sourceLines) {
        if (!startsBlockList(sourceLines)) {
            return Optional.of(FrontmatterList.nonList(sourceLines));
        }
        return new BlockListParser(sourceLines).parse();
    }

    private static boolean startsBlockList(List<String> sourceLines) {
        return !sourceLines.isEmpty() && sourceLines.get(0).stripLeading().startsWith("- ");
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
            Map<String, FrontmatterList> structuredValues,
            String key) {
        return values.containsKey(key) || structuredValues.containsKey(key);
    }

    private static Map<String, FrontmatterList> immutableStructuredValues(
            Map<String, FrontmatterList> structuredValues) {
        Map<String, FrontmatterList> copied = new LinkedHashMap<>(structuredValues);
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
            Optional<String> key = normalizedKey(line.substring(0, colon).strip());
            if (key.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new FrontmatterLine(key.get(), line.substring(colon + 1).strip()));
        }

        private static Optional<String> normalizedKey(String token) {
            return FrontmatterScalar.parse(token)
                    .flatMap(FrontmatterScalar::stringValue)
                    .filter(key -> !key.isEmpty());
        }

        private boolean emptyList() {
            return "[]".equals(token);
        }

        private boolean inlineMapping() {
            return token.startsWith("{") && token.endsWith("}");
        }

        private boolean startsStructuredValue(List<String> lines, int nextLineIndex) {
            return token.isEmpty()
                    && nextLineIndex < lines.size()
                    && indentation(lines.get(nextLineIndex)) > 0;
        }
    }

    private static final class BlockListParser {

        private final List<String> sourceLines;
        private final int itemIndent;
        private final List<Map<String, String>> mapEntries;
        private final List<String> scalarEntries;
        private CurrentEntry currentEntry;
        private boolean containsOnlyScalarMaps;
        private boolean containsOnlyScalars;

        private BlockListParser(List<String> sourceLines) {
            this.sourceLines = List.copyOf(sourceLines);
            this.itemIndent = indentation(sourceLines.get(0));
            this.mapEntries = new ArrayList<>();
            this.scalarEntries = new ArrayList<>();
            this.currentEntry = null;
            this.containsOnlyScalarMaps = true;
            this.containsOnlyScalars = true;
        }

        private Optional<FrontmatterList> parse() {
            for (String sourceLine : sourceLines) {
                if (!accept(sourceLine)) {
                    return Optional.empty();
                }
            }
            finishCurrentEntry();
            return Optional.of(FrontmatterList.list(
                    mapEntries,
                    scalarEntries,
                    sourceLines,
                    containsOnlyScalarMaps,
                    containsOnlyScalars));
        }

        private boolean accept(String sourceLine) {
            int indent = indentation(sourceLine);
            String content = sourceLine.substring(indent);
            if (startsEntry(indent, content)) {
                return startEntry(content.substring(2));
            }
            if (currentEntry == null || indent <= itemIndent) {
                return false;
            }
            currentEntry.recordContinuation(indent, content);
            return true;
        }

        private boolean startsEntry(int indent, String content) {
            return indent == itemIndent && content.startsWith("- ");
        }

        private boolean startEntry(String firstField) {
            finishCurrentEntry();
            currentEntry = CurrentEntry.parse(firstField, itemIndent);
            containsOnlyScalarMaps &= currentEntry.hasScalarMapShape();
            containsOnlyScalars &= currentEntry.hasScalarShape();
            return true;
        }

        private void finishCurrentEntry() {
            if (currentEntry != null) {
                currentEntry.recordInto(mapEntries, scalarEntries);
                containsOnlyScalarMaps &= currentEntry.hasScalarMapShape();
                containsOnlyScalars &= currentEntry.hasScalarShape();
                currentEntry = null;
            }
        }

        private static final class CurrentEntry {

            private final Map<String, String> mapValue;
            private final String scalarValue;
            private final int itemIndent;
            private boolean scalarMapShape;
            private boolean scalarShape;

            private CurrentEntry(
                    Map<String, String> mapValue,
                    String scalarValue,
                    int itemIndent,
                    boolean scalarMapShape,
                    boolean scalarShape) {
                this.mapValue = mapValue;
                this.scalarValue = scalarValue;
                this.itemIndent = itemIndent;
                this.scalarMapShape = scalarMapShape;
                this.scalarShape = scalarShape;
            }

            private static CurrentEntry parse(String token, int itemIndent) {
                Optional<String> scalarValue = FrontmatterScalar.parse(token)
                        .flatMap(FrontmatterScalar::listStringValue);
                if (scalarValue.isPresent()) {
                    return new CurrentEntry(null, scalarValue.get(), itemIndent, false, true);
                }
                Map<String, String> mapValue = new LinkedHashMap<>();
                if (addMapEntry(mapValue, token)) {
                    return new CurrentEntry(mapValue, null, itemIndent, true, false);
                }
                return new CurrentEntry(null, null, itemIndent, false, false);
            }

            private void recordContinuation(int indent, String content) {
                scalarShape = false;
                if (mapValue != null && indent == itemIndent + 2) {
                    if (!addMapEntry(mapValue, content)) {
                        scalarMapShape = false;
                    }
                    return;
                }
                scalarMapShape = false;
            }

            private boolean hasScalarMapShape() {
                return scalarMapShape;
            }

            private boolean hasScalarShape() {
                return scalarShape;
            }

            private void recordInto(List<Map<String, String>> mapEntries, List<String> scalarEntries) {
                if (mapValue != null) {
                    mapEntries.add(immutableMap(mapValue));
                } else if (scalarValue != null) {
                    scalarEntries.add(scalarValue);
                }
            }
        }
    }

    private record FrontmatterList(
            List<Map<String, String>> entries,
            List<String> scalarEntries,
            List<String> sourceLines,
            boolean containsOnlyScalarMaps,
            boolean containsOnlyScalars,
            boolean listShape) {

        private FrontmatterList {
            entries = entries.stream().map(MarkdownNote::immutableMap).toList();
            scalarEntries = List.copyOf(scalarEntries);
            sourceLines = List.copyOf(sourceLines);
        }

        private static FrontmatterList empty() {
            return new FrontmatterList(List.of(), List.of(), List.of(), true, true, true);
        }

        private static FrontmatterList nonList(List<String> sourceLines) {
            return new FrontmatterList(List.of(), List.of(), sourceLines, false, false, false);
        }

        private static FrontmatterList list(
                List<Map<String, String>> entries,
                List<String> scalarEntries,
                List<String> sourceLines,
                boolean containsOnlyScalarMaps,
                boolean containsOnlyScalars) {
            return new FrontmatterList(
                    entries,
                    scalarEntries,
                    sourceLines,
                    containsOnlyScalarMaps,
                    containsOnlyScalars,
                    true);
        }

        private boolean populated() {
            return !sourceLines.isEmpty();
        }

        private String yamlFor(String key) {
            return key + ":\n" + String.join("\n", sourceLines) + '\n';
        }
    }

    private record ParsedMapList(FrontmatterList frontmatterList, int nextLineIndex) {

        private static ParsedMapList of(FrontmatterList frontmatterList, int nextLineIndex) {
            return new ParsedMapList(frontmatterList, nextLineIndex);
        }
    }

    public enum HeaderState {
        PRESENT,
        ABSENT,
        MALFORMED
    }

    public enum StructuredField {
        ABSENT,
        EMPTY_LIST,
        POPULATED_LIST,
        NON_LIST
    }
}
