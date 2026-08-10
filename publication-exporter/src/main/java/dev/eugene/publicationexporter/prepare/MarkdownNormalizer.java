package dev.eugene.publicationexporter.prepare;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarkdownNormalizer {

    private static final Pattern FENCE_OPEN = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$");
    private static final Pattern FENCE_CLOSE = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})[ \\t]*$");
    private static final Pattern INLINE_CODE = Pattern.compile("(?s)(?<!\\\\)(`+)(?!`).*?\\1(?!`)");
    private static final String COMMENT_MARKER = "%%";

    private MarkdownNormalizer() {
    }

    public static MarkdownNormalizationOutcome normalize(String body) {
        StringBuilder output = new StringBuilder(body.length());
        int cursor = 0;
        while (cursor < body.length()) {
            ProtectedSpan protectedSpan = nextProtectedSpan(body, cursor);
            int commentStart = body.indexOf(COMMENT_MARKER, cursor);

            if (protectedSpan != null && (commentStart < 0 || protectedSpan.start() <= commentStart)) {
                output.append(body, cursor, protectedSpan.end());
                cursor = protectedSpan.end();
            } else if (commentStart >= 0) {
                int commentEnd = body.indexOf(COMMENT_MARKER, commentStart + COMMENT_MARKER.length());
                if (commentEnd < 0) {
                    return MarkdownNormalizationOutcome.unclosedComment(commentStart);
                }
                output.append(body, cursor, commentStart);
                cursor = commentEnd + COMMENT_MARKER.length();
            } else {
                break;
            }
        }
        output.append(body, cursor, body.length());
        return MarkdownNormalizationOutcome.normalized(output.toString());
    }

    private static ProtectedSpan nextProtectedSpan(String body, int cursor) {
        ProtectedSpan fenced = fencedSpan(body, cursor);
        ProtectedSpan inline = inlineCodeSpan(body, cursor);
        if (fenced == null) {
            return inline;
        }
        if (inline == null) {
            return fenced;
        }
        return fenced.start() <= inline.start() ? fenced : inline;
    }

    private static ProtectedSpan inlineCodeSpan(String body, int cursor) {
        Matcher matcher = INLINE_CODE.matcher(body);
        return matcher.find(cursor) ? new ProtectedSpan(matcher.start(), matcher.end()) : null;
    }

    private static ProtectedSpan fencedSpan(String body, int cursor) {
        Matcher opening = FENCE_OPEN.matcher(body);
        int searchFrom = cursor;
        while (opening.find(searchFrom)) {
            String fenceChar = opening.group(1);
            String infoString = opening.group(2);
            if (fenceChar.charAt(0) == '`' && infoString.contains("`")) {
                searchFrom = lineEndingEnd(body, opening.end());
                continue;
            }
            int closingSearchFrom = lineEndingEnd(body, opening.end());
            Matcher closing = FENCE_CLOSE.matcher(body);
            while (closing.find(closingSearchFrom)) {
                String closeChar = closing.group(1);
                if (closeChar.charAt(0) == fenceChar.charAt(0) && closeChar.length() >= fenceChar.length()) {
                    return new ProtectedSpan(opening.start(), lineEndingEnd(body, closing.end()));
                }
                closingSearchFrom = lineEndingEnd(body, closing.end());
            }
            return new ProtectedSpan(opening.start(), body.length());
        }
        return null;
    }

    private static int lineEndingEnd(String body, int position) {
        if (body.startsWith("\r\n", position)) {
            return position + 2;
        }
        if (position < body.length() && (body.charAt(position) == '\r' || body.charAt(position) == '\n')) {
            return position + 1;
        }
        return position;
    }

    private record ProtectedSpan(int start, int end) {
    }
}
