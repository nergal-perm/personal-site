package dev.eugene.publicationexporter.prepare;

import java.util.ArrayList;
import java.util.List;

final class OccurrenceLabelMarkers {

    private static final char DELIMITER_OPEN = '';
    private static final char DELIMITER_CLOSE = '';

    private OccurrenceLabelMarkers() {
    }

    static String delimit(String resolvedBody, List<LinkOccurrence> occurrences) {
        StringBuilder delimited = new StringBuilder(resolvedBody.length() + occurrences.size() * 2);
        int cursor = 0;
        for (LinkOccurrence occurrence : occurrences) {
            delimited.append(resolvedBody, cursor, occurrence.spanStart());
            delimited.append(DELIMITER_OPEN);
            delimited.append(resolvedBody, occurrence.spanStart(), occurrence.spanEnd());
            delimited.append(DELIMITER_CLOSE);
            cursor = occurrence.spanEnd();
        }
        delimited.append(resolvedBody, cursor, resolvedBody.length());
        return delimited.toString();
    }

    static List<String> scan(String delimitedBody) {
        List<String> spans = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int open = delimitedBody.indexOf(DELIMITER_OPEN, cursor);
            if (open < 0) {
                break;
            }
            int close = delimitedBody.indexOf(DELIMITER_CLOSE, open + 1);
            if (close < 0) {
                break;
            }
            spans.add(delimitedBody.substring(open + 1, close));
            cursor = close + 1;
        }
        return spans;
    }

    static String strip(String delimitedBody) {
        StringBuilder stripped = new StringBuilder(delimitedBody.length());
        for (int i = 0; i < delimitedBody.length(); i++) {
            char c = delimitedBody.charAt(i);
            if (c != DELIMITER_OPEN && c != DELIMITER_CLOSE) {
                stripped.append(c);
            }
        }
        return stripped.toString();
    }
}
