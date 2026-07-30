package dev.eugene.astroexport.references;

import dev.eugene.astroexport.markdown.MarkdownScanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser and projector for late-bound semantic markdown references. */
public final class SemanticReferenceMarkdown {
  private static final Pattern LINK = Pattern.compile("\\[(?<label>[^\\]\\n]*?)\\]\\((?<destination>[^\\r\\n)]*?)\\)");
  private static final Pattern REFERENCE_LINK = Pattern.compile("^ref:([A-Za-z0-9-]+)(?<heading>#[^)]*)?$");
  private static final Pattern HEADING_CLEAN = Pattern.compile("[^\\w\\s-]");
  private static final Pattern HEADING_SPACER = Pattern.compile("[\\s_-]+");

  private SemanticReferenceMarkdown() { }

  public static List<Occurrence> occurrences(String markdown) {
    List<Occurrence> occurrences = new ArrayList<>();
    int cursor = 0;
    for (MarkdownScanner.Span span : MarkdownScanner.protectedSpans(markdown)) {
      collect(markdown.substring(cursor, span.start()), occurrences);
      cursor = span.end();
    }
    collect(markdown.substring(cursor), occurrences);
    return List.copyOf(occurrences);
  }

  public static String project(
      String markdown,
      PageReferenceMap map,
      Function<PageReferenceMap.Reference, Optional<String>> href) {
    return replaceOutsideProtectedContexts(markdown, occurrence -> {
      PageReferenceMap.Reference reference = requiredReference(map, occurrence.id());
      Optional<String> destination = href.apply(reference);
      return destination
          .map(value -> "[" + occurrence.label() + "](" + value + ")")
          .orElse(occurrence.label());
    });
  }

  public static String normalizeHeadingFragment(String heading) {
    if (heading == null) return "";
    String value = heading.strip();
    if (value.isEmpty() || !value.startsWith("#")) return "";
    String normalized = HEADING_CLEAN.matcher(
        HEADING_SPACER.matcher(value.substring(1).strip().toLowerCase(Locale.ROOT)).replaceAll("-"))
        .replaceAll("");
    normalized = normalized.replaceAll("^-|-$", "");
    return normalized.isEmpty() ? "" : "#" + normalized;
  }

  public static record Occurrence(String id, String label, String heading) { }

  private static void collect(String segment, List<Occurrence> collector) {
    Matcher matcher = LINK.matcher(segment);
    while (matcher.find()) {
      if (isEscaped(segment, matcher.start())) {
        continue;
      }
      Matcher destination = REFERENCE_LINK.matcher(matcher.group("destination"));
      if (!destination.matches()) {
        continue;
      }
      collector.add(new Occurrence(
          destination.group(1),
          matcher.group("label"),
          destination.group("heading") == null ? "" : normalizeHeadingFragment(destination.group("heading"))));
    }
  }

  private static String replaceOutsideProtectedContexts(String markdown, Function<Occurrence, String> replacement) {
    StringBuilder result = new StringBuilder();
    int cursor = 0;
    for (MarkdownScanner.Span span : MarkdownScanner.protectedSpans(markdown)) {
      result.append(replace(markdown.substring(cursor, span.start()), replacement));
      result.append(markdown, span.start(), span.end());
      cursor = span.end();
    }
    return result.append(replace(markdown.substring(cursor), replacement)).toString();
  }

  private static String replace(String source, Function<Occurrence, String> replacement) {
    Matcher matcher = LINK.matcher(source);
    StringBuilder result = new StringBuilder(source.length());
    int cursor = 0;
    while (matcher.find()) {
      if (isEscaped(source, matcher.start())) {
        continue;
      }
      Matcher destination = REFERENCE_LINK.matcher(matcher.group("destination"));
      if (!destination.matches()) {
        continue;
      }
      result.append(source, cursor, matcher.start());
      result.append(replacement.apply(new Occurrence(
          destination.group(1),
          matcher.group("label"),
          destination.group("heading") == null ? "" : normalizeHeadingFragment(destination.group("heading")))));
      cursor = matcher.end();
    }
    result.append(source.substring(cursor));
    return result.toString();
  }

  private static PageReferenceMap.Reference requiredReference(PageReferenceMap map, String id) {
    PageReferenceMap.Reference reference = map.references().get(id);
    if (reference == null) {
      throw new IllegalArgumentException("unknown semantic reference id: " + id);
    }
    return reference;
  }

  private static boolean isEscaped(String source, int index) {
    int cursor = index - 1;
    int escapes = 0;
    while (cursor >= 0 && source.charAt(cursor) == '\\') {
      escapes++;
      cursor--;
    }
    return (escapes & 1) == 1;
  }
}
