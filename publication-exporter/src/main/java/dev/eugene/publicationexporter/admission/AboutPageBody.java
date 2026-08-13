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
        String text = joinNonBlank(lines.subList(start + 1, end));
        if (text.isBlank()) {
            throw new MalformedBodyException("## " + heading + " must contain non-empty prose");
        }
        return text;
    }

    private static List<Principle> requiredPrinciples(List<String> lines) {
        int start = sectionStart(lines, "Принципы");
        int end = nextH2Or(lines, start + 1, lines.size());
        List<Principle> principles = new ArrayList<>();
        List<String> section = lines.subList(start + 1, end);
        int index = 0;
        while (index < section.size()) {
            String line = section.get(index);
            if (line.startsWith("### ")) {
                String title = line.substring(4).strip();
                int principleEnd = nextH3Or(section, index + 1, section.size());
                String text = joinNonBlank(section.subList(index + 1, principleEnd));
                if (title.isBlank() || text.isBlank()) {
                    throw new MalformedBodyException("## Принципы subsection must have a non-blank heading and prose");
                }
                principles.add(new Principle(title, text));
                index = principleEnd;
            } else {
                index++;
            }
        }
        if (principles.isEmpty()) {
            throw new MalformedBodyException("## Принципы must contain at least one ### subsection");
        }
        return principles;
    }

    private static int sectionStart(List<String> lines, String heading) {
        String marker = "## " + heading;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(marker)) {
                return i;
            }
        }
        throw new MalformedBodyException("Missing required heading `" + marker + "`");
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

    private static String joinNonBlank(List<String> lines) {
        return lines.stream()
                .filter(line -> !line.isBlank())
                .map(String::strip)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
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
