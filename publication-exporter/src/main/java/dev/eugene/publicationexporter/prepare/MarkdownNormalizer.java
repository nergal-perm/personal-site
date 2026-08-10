package dev.eugene.publicationexporter.prepare;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarkdownNormalizer {

    private static final Pattern FENCE_OPEN = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$");
    private static final Pattern FENCE_CLOSE = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})[ \\t]*$");
    private static final Pattern INLINE_CODE = Pattern.compile("(?s)(?<!\\\\)(?<!`)(`+)(?!`).*?(?<!`)\\1(?!`)");
    private static final String COMMENT_MARKER = "%%";

    private MarkdownNormalizer() {
    }

    public static MarkdownNormalizationOutcome normalize(String body) {
        StringBuilder output = new StringBuilder(body.length());
        int cursor = 0;
        while (cursor < body.length()) {
            ProtectedSpan protectedSpan = nextProtectedSpan(body, cursor);
            int commentStart = nextCommentStart(body, cursor);
            if (protectedSpanBeforeComment(protectedSpan, commentStart)) {
                cursor = copyProtectedSpan(body, output, cursor, protectedSpan);
            } else if (commentStart >= 0) {
                Optional<Integer> commentEnd = commentEnd(body, commentStart);
                if (commentEnd.isEmpty()) {
                    return MarkdownNormalizationOutcome.unclosedComment(commentStart);
                }
                cursor = skipComment(body, output, cursor, commentStart, commentEnd.get());
            } else {
                break;
            }
        }
        output.append(body, cursor, body.length());
        return MarkdownNormalizationOutcome.normalized(output.toString());
    }

    private static int nextCommentStart(String body, int cursor) {
        return body.indexOf(COMMENT_MARKER, cursor);
    }

    private static boolean protectedSpanBeforeComment(ProtectedSpan protectedSpan, int commentStart) {
        return protectedSpan != null && (commentStart < 0 || protectedSpan.start() <= commentStart);
    }

    private static int copyProtectedSpan(
            String body, StringBuilder output, int cursor, ProtectedSpan protectedSpan) {
        output.append(body, cursor, protectedSpan.end());
        return protectedSpan.end();
    }

    private static Optional<Integer> commentEnd(String body, int commentStart) {
        int closingMarker = body.indexOf(COMMENT_MARKER, commentStart + COMMENT_MARKER.length());
        return closingMarker < 0 ? Optional.empty() : Optional.of(closingMarker);
    }

    private static int skipComment(
            String body, StringBuilder output, int cursor, int commentStart, int commentEnd) {
        output.append(body, cursor, commentStart);
        return commentEnd + COMMENT_MARKER.length();
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
            if (invalidFenceOpening(fenceChar, infoString)) {
                searchFrom = lineEndingEnd(body, opening.end());
                continue;
            }
            return fenceSpan(body, opening, fenceChar);
        }
        return null;
    }

    private static boolean invalidFenceOpening(String fenceChar, String infoString) {
        return fenceChar.charAt(0) == '`' && infoString.contains("`");
    }

    private static ProtectedSpan fenceSpan(String body, Matcher opening, String fenceChar) {
        Optional<Integer> closingEnd = closingFenceEnd(body, opening, fenceChar);
        return new ProtectedSpan(opening.start(), closingEnd.orElse(body.length()));
    }

    private static Optional<Integer> closingFenceEnd(String body, Matcher opening, String fenceChar) {
        int searchFrom = lineEndingEnd(body, opening.end());
        Matcher closing = FENCE_CLOSE.matcher(body);
        while (closing.find(searchFrom)) {
            if (matchingFence(fenceChar, closing.group(1))) {
                return Optional.of(lineEndingEnd(body, closing.end()));
            }
            searchFrom = lineEndingEnd(body, closing.end());
        }
        return Optional.empty();
    }

    private static boolean matchingFence(String opening, String closing) {
        return closing.charAt(0) == opening.charAt(0) && closing.length() >= opening.length();
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
