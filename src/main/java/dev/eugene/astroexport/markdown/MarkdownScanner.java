package dev.eugene.astroexport.markdown;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds Markdown regions where rendered Markdown syntax must not be interpreted. */
public final class MarkdownScanner {
  private static final Pattern FENCE_OPEN = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$");
  private static final Pattern FENCE_CLOSE = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})[ \\t]*$");
  private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?(?:-->|\\z)");
  private static final Pattern INLINE_CODE = Pattern.compile("(?s)(?<!\\\\)(`+)(?!`).*?\\1(?!`)");
  private static final Pattern RAW_HTML_PRE_OPEN = Pattern.compile("(?im)^ {0,3}<pre(?=[\\t\\r\\n />])");
  private static final Pattern RAW_HTML_PRE_CLOSE = Pattern.compile("(?i)</pre\\s*>");
  private static final Pattern ESCAPED_WIKILINK = Pattern.compile("\\\\!?\\[\\[[^\\]\\n]*\\]\\]");

  private MarkdownScanner() { }

  public static Optional<String> section(String body, String heading) {
    String masked = maskProtectedContexts(body);
    Pattern pattern = Pattern.compile("(?ms)^##[ \\t]+" + Pattern.quote(heading)
        + "[ \\t]*(?:\\r\\n|\\r|\\n|\\z)(.*?)(?=^##[ \\t]+[^\\r\\n]*(?:\\r\\n|\\r|\\n|\\z)|\\z)");
    Matcher match = pattern.matcher(masked);
    if (!match.find()) return Optional.empty();
    return Optional.of(body.substring(match.start(1), match.end(1)).strip());
  }

  public static List<String> listItems(String body, String heading) {
    return section(body, heading).map(value -> {
      List<String> items = new ArrayList<>();
      for (String line : value.split("\\R")) {
        Matcher match = Pattern.compile("^\\s*-\\s+(.+?)\\s*$").matcher(line);
        if (match.matches()) items.add(match.group(1).strip());
      }
      return List.copyOf(items);
    }).orElseGet(List::of);
  }

  public static String stripObsidianComments(String body) {
    return process(body, EnumSet.of(Kind.OBSIDIAN_COMMENT), false);
  }

  public static String stripMarkdownComments(String body) {
    return process(body, EnumSet.of(Kind.OBSIDIAN_COMMENT, Kind.HTML_COMMENT), false);
  }

  public static String maskProtectedContexts(String body) {
    return process(body, EnumSet.allOf(Kind.class), true);
  }

  public static List<Span> protectedSpans(String body) {
    List<Span> spans = new ArrayList<>();
    int cursor = 0;
    while (cursor < body.length()) {
      Span next = nextSpan(body, cursor);
      if (next == null) break;
      spans.add(next);
      cursor = next.end();
    }
    return List.copyOf(spans);
  }

  private static String process(String body, EnumSet<Kind> kinds, boolean mask) {
    StringBuilder result = new StringBuilder(body.length());
    int cursor = 0;
    for (Span span : protectedSpans(body)) {
      if (!kinds.contains(span.kind())) continue;
      result.append(body, cursor, span.start());
      if (mask) {
        for (int i = span.start(); i < span.end(); i++) {
          char character = body.charAt(i);
          result.append(character == '\r' || character == '\n' ? character : ' ');
        }
      }
      cursor = span.end();
    }
    result.append(body, cursor, body.length());
    return result.toString();
  }

  private static Span nextSpan(String body, int cursor) {
    List<Span> candidates = new ArrayList<>();
    add(candidates, fencedSpan(body, cursor));
    add(candidates, patternSpan(body, cursor, HTML_COMMENT, Kind.HTML_COMMENT));
    add(candidates, rawPreSpan(body, cursor));
    add(candidates, patternSpan(body, cursor, INLINE_CODE, Kind.INLINE_CODE));
    add(candidates, patternSpan(body, cursor, ESCAPED_WIKILINK, Kind.ESCAPED_WIKILINK));
    int obsidianStart = body.indexOf("%%", cursor);
    if (obsidianStart >= 0) {
      int closing = body.indexOf("%%", obsidianStart + 2);
      candidates.add(new Span(obsidianStart, closing < 0 ? body.length() : closing + 2, Kind.OBSIDIAN_COMMENT));
    }
    return candidates.stream().min(Comparator.comparingInt(Span::start)).orElse(null);
  }

  private static void add(List<Span> spans, Span span) {
    if (span != null) spans.add(span);
  }

  private static Span fencedSpan(String body, int cursor) {
    Matcher opening = FENCE_OPEN.matcher(body);
    while (opening.find(cursor)) {
      String fence = opening.group(1);
      if (fence.charAt(0) == '`' && opening.group(2).contains("`")) {
        cursor = lineEndingEnd(body, opening.end());
        continue;
      }
      Matcher closing = FENCE_CLOSE.matcher(body);
      while (closing.find(lineEndingEnd(body, opening.end()))) {
        String closeFence = closing.group(1);
        if (closeFence.charAt(0) == fence.charAt(0) && closeFence.length() >= fence.length()) {
          return new Span(opening.start(), lineEndingEnd(body, closing.end()), Kind.FENCED_CODE);
        }
      }
      return new Span(opening.start(), body.length(), Kind.FENCED_CODE);
    }
    return null;
  }

  private static Span rawPreSpan(String body, int cursor) {
    Matcher opening = RAW_HTML_PRE_OPEN.matcher(body);
    if (!opening.find(cursor)) return null;
    Matcher closing = RAW_HTML_PRE_CLOSE.matcher(body);
    return new Span(opening.start(), closing.find(opening.end()) ? closing.end() : body.length(), Kind.RAW_HTML_PRE);
  }

  private static Span patternSpan(String body, int cursor, Pattern pattern, Kind kind) {
    Matcher match = pattern.matcher(body);
    return match.find(cursor) ? new Span(match.start(), match.end(), kind) : null;
  }

  private static int lineEndingEnd(String body, int position) {
    if (body.startsWith("\r\n", position)) return position + 2;
    return position < body.length() && (body.charAt(position) == '\r' || body.charAt(position) == '\n') ? position + 1 : position;
  }

  public record Span(int start, int end, Kind kind) { }

  public enum Kind { FENCED_CODE, HTML_COMMENT, OBSIDIAN_COMMENT, INLINE_CODE, RAW_HTML_PRE, ESCAPED_WIKILINK }
}
