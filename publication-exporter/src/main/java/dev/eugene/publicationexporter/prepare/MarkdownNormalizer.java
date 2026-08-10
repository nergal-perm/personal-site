package dev.eugene.publicationexporter.prepare;

import java.util.Optional;

public final class MarkdownNormalizer {

    private static final String COMMENT_MARKER = "%%";

    private MarkdownNormalizer() {
    }

    public static MarkdownNormalizationOutcome normalize(String body) {
        StringBuilder output = new StringBuilder(body.length());
        int cursor = 0;
        while (cursor < body.length()) {
            ProtectedRegionScanner.ProtectedSpan protectedSpan = ProtectedRegionScanner.nextProtectedSpan(body, cursor);
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

    private static boolean protectedSpanBeforeComment(
            ProtectedRegionScanner.ProtectedSpan protectedSpan, int commentStart) {
        return protectedSpan != null && (commentStart < 0 || protectedSpan.start() <= commentStart);
    }

    private static int copyProtectedSpan(
            String body, StringBuilder output, int cursor, ProtectedRegionScanner.ProtectedSpan protectedSpan) {
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

}
