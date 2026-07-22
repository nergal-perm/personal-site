package dev.eugene.astroexport.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class MarkdownProtection {
  private static final Pattern FENCE_OPEN = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$");
  private static final Pattern FENCE_CLOSE = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})[ \\t]*$");
  private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?(?:-->|\\z)");
  private static final Pattern RAW_HTML_PRE_OPEN = Pattern.compile("(?im)^ {0,3}<pre(?=[\\t\\r\\n />])");
  private static final Pattern RAW_HTML_PRE_CLOSE = Pattern.compile("(?i)</pre\\s*>");
  private static final Pattern INLINE_CODE = Pattern.compile("(?s)(?<!\\\\)(`+)(?!`).*?\\1(?!`)");

  private MarkdownProtection() { }

  static String mask(String body) {
    StringBuilder result = new StringBuilder(body.length());
    int cursor = 0;
    for (Range range : ranges(body)) {
      result.append(body, cursor, range.start());
      result.append(body.substring(range.start(), range.end()).replaceAll("[^\\r\\n]", " "));
      cursor = range.end();
    }
    result.append(body, cursor, body.length());
    return result.toString();
  }

  static boolean contains(String body, int offset) {
    return ranges(body).stream().anyMatch(range -> range.start() <= offset && offset < range.end());
  }

  private static List<Range> ranges(String body) {
    List<Range> ranges = new ArrayList<>();
    int cursor = 0;
    while (cursor < body.length()) {
      Range range = nextRange(body, cursor);
      if (range == null) break;
      ranges.add(range);
      cursor = range.end();
    }
    return ranges;
  }

  private static Range nextRange(String body, int cursor) {
    Range candidate = earliest(fencedRange(body, cursor), patternRange(HTML_COMMENT, body, cursor),
        rawHtmlPreRange(body, cursor), patternRange(INLINE_CODE, body, cursor));
    int obsidianStart = body.indexOf("%%", cursor);
    if (obsidianStart >= 0 && (candidate == null || obsidianStart < candidate.start())) {
      int closing = body.indexOf("%%", obsidianStart + 2);
      return new Range(obsidianStart, closing < 0 ? body.length() : closing + 2);
    }
    return candidate;
  }

  private static Range fencedRange(String body, int cursor) {
    var opening = FENCE_OPEN.matcher(body);
    while (opening.find(cursor)) {
      String fence = opening.group(1);
      if (fence.charAt(0) == '`' && opening.group(2).contains("`")) { cursor = opening.end(); continue; }
      var closing = FENCE_CLOSE.matcher(body);
      while (closing.find(opening.end())) {
        String closingFence = closing.group(1);
        if (closingFence.charAt(0) == fence.charAt(0) && closingFence.length() >= fence.length()) {
          return new Range(opening.start(), closing.end());
        }
      }
      return new Range(opening.start(), body.length());
    }
    return null;
  }

  private static Range rawHtmlPreRange(String body, int cursor) {
    var opening = RAW_HTML_PRE_OPEN.matcher(body);
    if (!opening.find(cursor)) return null;
    var closing = RAW_HTML_PRE_CLOSE.matcher(body);
    return new Range(opening.start(), closing.find(opening.end()) ? closing.end() : body.length());
  }

  private static Range patternRange(Pattern pattern, String body, int cursor) {
    var match = pattern.matcher(body);
    return match.find(cursor) ? new Range(match.start(), match.end()) : null;
  }

  private static Range earliest(Range... ranges) {
    Range earliest = null;
    for (Range range : ranges) if (range != null && (earliest == null || range.start() < earliest.start())) earliest = range;
    return earliest;
  }

  private record Range(int start, int end) { }
}
