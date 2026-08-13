package dev.eugene.publicationexporter.admission;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AboutPageBody {

    private final String summary;
    private final String eyebrow;
    private final String lead;
    private final List<Principle> principles;
    private final String colophon;

    private AboutPageBody(String summary, String eyebrow, String lead, List<Principle> principles, String colophon) {
        this.summary = summary;
        this.eyebrow = eyebrow;
        this.lead = lead;
        this.principles = List.copyOf(principles);
        this.colophon = colophon;
    }

    public static AboutPageBody parse(String body) {
        List<String> lines = List.of(body.split("\\R", -1));
        String summary = requiredSection(lines, "Кратко");
        String eyebrow = requiredSection(lines, "Eyebrow");
        String lead = requiredSection(lines, "Лид");
        List<Principle> principles = requiredPrinciples(lines);
        String colophon = requiredSection(lines, "Колофон");
        return new AboutPageBody(summary, eyebrow, lead, principles, colophon);
    }

    public String summary() {
        return summary;
    }

    public String eyebrow() {
        return eyebrow;
    }

    public String lead() {
        return lead;
    }

    public List<Principle> principles() {
        return principles;
    }

    public String colophon() {
        return colophon;
    }

    private static String requiredSection(List<String> lines, String heading) {
        int start = sectionStart(lines, heading);
        int end = nextH2Or(lines, start + 1, lines.size());
        String text = joinProse(lines.subList(start + 1, end));
        if (text.isBlank()) {
            throw new MalformedBodyException("## " + heading + " must contain non-empty prose");
        }
        return text;
    }

    private static List<Principle> requiredPrinciples(List<String> lines) {
        List<String> section = principlesSection(lines);
        List<Principle> principles = collectPrinciples(section);
        requireAtLeastOnePrinciple(principles);
        return principles;
    }

    private static List<String> principlesSection(List<String> lines) {
        int start = sectionStart(lines, "Принципы");
        int end = nextH2Or(lines, start + 1, lines.size());
        return lines.subList(start + 1, end);
    }

    private static List<Principle> collectPrinciples(List<String> section) {
        List<Principle> principles = new ArrayList<>();
        int index = 0;
        while (index < section.size()) {
            if (isPrincipleHeading(section.get(index))) {
                int principleEnd = nextH3Or(section, index + 1, section.size());
                principles.add(parsePrinciple(section, index, principleEnd));
                index = principleEnd;
            } else {
                index++;
            }
        }
        return principles;
    }

    private static boolean isPrincipleHeading(String line) {
        return line.startsWith("### ");
    }

    private static Principle parsePrinciple(List<String> section, int start, int end) {
        String title = section.get(start).substring(4).strip();
        String text = joinProse(section.subList(start + 1, end));
        return validatedPrinciple(title, text);
    }

    private static Principle validatedPrinciple(String title, String text) {
        if (title.isBlank() || text.isBlank()) {
            throw new MalformedBodyException("## Принципы subsection must have a non-blank heading and prose");
        }
        return new Principle(title, text);
    }

    private static void requireAtLeastOnePrinciple(List<Principle> principles) {
        if (principles.isEmpty()) {
            throw new MalformedBodyException("## Принципы must contain at least one ### subsection");
        }
    }

    private static int sectionStart(List<String> lines, String heading) {
        String marker = "## " + heading;
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(marker)) {
                if (start >= 0) {
                    throw new MalformedBodyException("Duplicate required heading `" + marker + "`");
                }
                start = i;
            }
        }
        if (start < 0) {
            throw new MalformedBodyException("Missing required heading `" + marker + "`");
        }
        return start;
    }

    private static int nextH2Or(List<String> lines, int from, int fallback) {
        for (int i = from; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## ")) {
                return i;
            }
        }
        return fallback;
    }

    private static int nextH3Or(List<String> lines, int from, int fallback) {
        for (int i = from; i < lines.size(); i++) {
            if (lines.get(i).startsWith("### ") || lines.get(i).startsWith("## ")) {
                return i;
            }
        }
        return fallback;
    }

    private static String joinProse(List<String> lines) {
        List<String> paragraphs = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                finishParagraph(paragraphs, current);
            } else {
                current.add(line.strip());
            }
        }
        finishParagraph(paragraphs, current);
        return String.join("\n\n", paragraphs);
    }

    private static void finishParagraph(List<String> paragraphs, List<String> current) {
        if (!current.isEmpty()) {
            paragraphs.add(String.join(" ", current));
            current.clear();
        }
    }

    public record Principle(String title, String text) {
        public Principle {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(text, "text");
        }
    }

    public static final class MalformedBodyException extends IllegalArgumentException {
        public MalformedBodyException(String message) {
            super(message);
        }
    }
}
