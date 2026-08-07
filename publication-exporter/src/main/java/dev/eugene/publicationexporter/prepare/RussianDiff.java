package dev.eugene.publicationexporter.prepare;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RussianDiff {

    public enum LineKind { UNCHANGED, ADDED, REMOVED }

    public record Line(LineKind kind, String text) {
        public Line {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
        }
    }

    private final List<Line> lines;

    private RussianDiff(List<Line> lines) {
        this.lines = List.copyOf(lines);
    }

    public static RussianDiff betweenBodies(String approvedBody, String currentBody) {
        return between(approvedBody, "", "", currentBody, "", "");
    }

    public static RussianDiff between(
            String approvedBody, String approvedTitle, String approvedDescription,
            String currentBody, String currentTitle, String currentDescription) {
        Objects.requireNonNull(approvedBody, "approvedBody");
        Objects.requireNonNull(approvedTitle, "approvedTitle");
        Objects.requireNonNull(approvedDescription, "approvedDescription");
        Objects.requireNonNull(currentBody, "currentBody");
        Objects.requireNonNull(currentTitle, "currentTitle");
        Objects.requireNonNull(currentDescription, "currentDescription");
        List<Line> completeDiff = new ArrayList<>();
        completeDiff.addAll(labeledFieldDiff("title", approvedTitle, currentTitle));
        completeDiff.addAll(labeledFieldDiff("description", approvedDescription, currentDescription));
        completeDiff.addAll(lcsDiff(normalize(approvedBody), normalize(currentBody)));
        return new RussianDiff(completeDiff);
    }

    public boolean isEmpty() {
        return lines.stream().allMatch(line -> line.kind() == LineKind.UNCHANGED);
    }

    public List<Line> lines() {
        return lines;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RussianDiff that)) {
            return false;
        }
        return lines.equals(that.lines);
    }

    @Override
    public int hashCode() {
        return lines.hashCode();
    }

    @Override
    public String toString() {
        return "RussianDiff[lines=" + lines + "]";
    }

    private static String[] normalize(String body) {
        if (body.isEmpty()) {
            return new String[0];
        }
        String withUnifiedLineEndings = body.replace("\r\n", "\n").replace("\r", "\n");
        String[] rawLines = withUnifiedLineEndings.split("\n", -1);
        String[] trimmed = new String[rawLines.length];
        for (int i = 0; i < rawLines.length; i++) {
            trimmed[i] = rawLines[i].stripTrailing();
        }
        return trimmed;
    }

    private static List<Line> lcsDiff(String[] oldLines, String[] newLines) {
        int oldLen = oldLines.length;
        int newLen = newLines.length;
        int[][] lengths = computeLcsLengths(oldLines, newLines);
        List<Line> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < oldLen && j < newLen) {
            if (oldLines[i].equals(newLines[j])) {
                result.add(new Line(LineKind.UNCHANGED, oldLines[i]));
                i++;
                j++;
            } else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
                result.add(new Line(LineKind.REMOVED, oldLines[i]));
                i++;
            } else {
                result.add(new Line(LineKind.ADDED, newLines[j]));
                j++;
            }
        }
        while (i < oldLen) {
            result.add(new Line(LineKind.REMOVED, oldLines[i]));
            i++;
        }
        while (j < newLen) {
            result.add(new Line(LineKind.ADDED, newLines[j]));
            j++;
        }
        return result;
    }

    private static List<Line> labeledFieldDiff(String label, String approved, String current) {
        List<Line> fieldDiff = lcsDiff(normalize(approved), normalize(current));
        if (fieldDiff.stream().allMatch(line -> line.kind() == LineKind.UNCHANGED)) {
            return List.of();
        }
        return fieldDiff.stream()
                .map(line -> new Line(line.kind(), label + ": " + line.text()))
                .toList();
    }

    private static int[][] computeLcsLengths(String[] oldLines, String[] newLines) {
        int oldLen = oldLines.length;
        int newLen = newLines.length;
        int[][] lengths = new int[oldLen + 1][newLen + 1];
        for (int i = oldLen - 1; i >= 0; i--) {
            for (int j = newLen - 1; j >= 0; j--) {
                lengths[i][j] = oldLines[i].equals(newLines[j])
                        ? lengths[i + 1][j + 1] + 1
                        : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
            }
        }
        return lengths;
    }
}
