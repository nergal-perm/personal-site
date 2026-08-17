package dev.eugene.publicationexporter.prepare;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class OccurrenceLabelMarkers {

    static final int MAX_OCCURRENCES = 3200;
    private static final char MARKER_RANGE_START = '\uE000';
    private static final char CLOSE_MARKER_START = '\uEC80';
    private static final char MARKER_RANGE_END = '\uF8FF';

    private OccurrenceLabelMarkers() {
    }

    static String delimit(String resolvedBody, List<LinkOccurrence> occurrences) {
        if (occurrences.size() > MAX_OCCURRENCES) {
            throw new IllegalStateException(
                    "Cannot track more than " + MAX_OCCURRENCES + " semantic occurrences in one note.");
        }
        StringBuilder delimited = new StringBuilder(resolvedBody.length() + occurrences.size() * 2);
        int cursor = 0;
        for (int i = 0; i < occurrences.size(); i++) {
            LinkOccurrence occurrence = occurrences.get(i);
            delimited.append(resolvedBody, cursor, occurrence.spanStart());
            delimited.append(openMarker(i));
            delimited.append(resolvedBody, occurrence.spanStart(), occurrence.spanEnd());
            delimited.append(closeMarker(i));
            cursor = occurrence.spanEnd();
        }
        delimited.append(resolvedBody, cursor, resolvedBody.length());
        return delimited.toString();
    }

    static Map<Integer, String> scan(String delimitedBody) {
        Map<Integer, String> spans = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        int cursor = 0;
        while (cursor < delimitedBody.length()) {
            MarkerScan found = nextIndexedSpan(delimitedBody, cursor);
            found.span().ifPresent(span -> recordSpan(spans, seen, span));
            cursor = found.nextCursor();
        }
        return spans;
    }

    private static MarkerScan nextIndexedSpan(String delimitedBody, int cursor) {
        char candidate = delimitedBody.charAt(cursor);
        if (!isOpenMarker(candidate)) {
            return new MarkerScan(cursor + 1, Optional.empty());
        }
        int index = candidate - MARKER_RANGE_START;
        char expectedClose = closeMarker(index);
        int close = closeMarkerPositionIfWellFormed(delimitedBody, cursor + 1, expectedClose);
        if (close < 0) {
            // Either there's no matching close marker at all, or another marker character
            // appears nested inside before it — in both cases the structure is malformed, so
            // this open marker is not consumed as a real pair; only advance past it and let the
            // scan continue linearly, so any well-formed marker inside the malformed span can
            // still be found on its own.
            return new MarkerScan(cursor + 1, Optional.empty());
        }
        String label = delimitedBody.substring(cursor + 1, close);
        return new MarkerScan(close + 1, Optional.of(new IndexedSpan(index, label)));
    }

    private static int closeMarkerPositionIfWellFormed(String delimitedBody, int from, char expectedClose) {
        for (int i = from; i < delimitedBody.length(); i++) {
            char c = delimitedBody.charAt(i);
            if (c == expectedClose) {
                return i;
            }
            if (isReservedCharacter(c)) {
                return -1;
            }
        }
        return -1;
    }

    private static void recordSpan(Map<Integer, String> spans, Set<Integer> seen, IndexedSpan span) {
        if (!seen.add(span.index())) {
            // A repeated index means the translation duplicated this occurrence's marker pair;
            // there's no way to tell which copy is authoritative, so it must not be treated as
            // present exactly once.
            spans.remove(span.index());
            return;
        }
        spans.put(span.index(), span.label());
    }

    private record MarkerScan(int nextCursor, Optional<IndexedSpan> span) {
    }

    private record IndexedSpan(int index, String label) {
    }

    static String strip(String delimitedBody) {
        StringBuilder stripped = new StringBuilder(delimitedBody.length());
        for (int i = 0; i < delimitedBody.length(); i++) {
            char c = delimitedBody.charAt(i);
            if (!isReservedCharacter(c)) {
                stripped.append(c);
            }
        }
        return stripped.toString();
    }

    static boolean containsReservedCharacters(String body) {
        for (int i = 0; i < body.length(); i++) {
            if (isReservedCharacter(body.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    static char openMarker(int index) {
        requireValidIndex(index);
        return (char) (MARKER_RANGE_START + index);
    }

    static char closeMarker(int index) {
        requireValidIndex(index);
        return (char) (CLOSE_MARKER_START + index);
    }

    private static boolean isOpenMarker(char candidate) {
        return candidate >= MARKER_RANGE_START && candidate < CLOSE_MARKER_START;
    }

    private static boolean isReservedCharacter(char candidate) {
        return candidate >= MARKER_RANGE_START && candidate <= MARKER_RANGE_END;
    }

    private static void requireValidIndex(int index) {
        if (index < 0 || index >= MAX_OCCURRENCES) {
            throw new IllegalArgumentException("Occurrence marker index is out of range: " + index);
        }
    }
}
