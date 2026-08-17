package dev.eugene.publicationexporter.prepare;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        int cursor = 0;
        while (cursor < delimitedBody.length()) {
            char candidate = delimitedBody.charAt(cursor);
            if (!isOpenMarker(candidate)) {
                cursor++;
                continue;
            }
            int index = candidate - MARKER_RANGE_START;
            int close = delimitedBody.indexOf(closeMarker(index), cursor + 1);
            if (close < 0) {
                cursor++;
                continue;
            }
            spans.put(index, delimitedBody.substring(cursor + 1, close));
            cursor = close + 1;
        }
        return spans;
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
