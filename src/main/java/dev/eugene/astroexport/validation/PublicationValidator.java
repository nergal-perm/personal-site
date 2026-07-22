package dev.eugene.astroexport.validation;

import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.PublicationKind;
import dev.eugene.astroexport.model.PublicationRequirement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class PublicationValidator {
  private static final Pattern PUBLIC_ID = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
  private static final Set<String> EDITORIAL_PAGES = Set.of(
      "home", "essays", "claims", "notes", "music", "library", "concepts", "now", "about");
  private static final Pattern FENCE_OPEN = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$");
  private static final Pattern FENCE_CLOSE = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})[ \\t]*$");
  private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?(?:-->|\\z)");
  private static final Pattern RAW_HTML_PRE_OPEN = Pattern.compile("(?im)^ {0,3}<pre(?=[\\t\\r\\n />])");
  private static final Pattern RAW_HTML_PRE_CLOSE = Pattern.compile("(?i)</pre\\s*>");
  private static final Pattern INLINE_CODE = Pattern.compile("(?s)(?<!\\\\)(`+)(?!`).*?\\1(?!`)");

  public List<PublicationDiagnostic> validate(Note note) {
    return validate(note.frontmatter(), note.body());
  }

  public List<PublicationDiagnostic> validate(Map<String, Object> frontmatter, String body) {
    List<PublicationDiagnostic> diagnostics = new ArrayList<>();
    String collection = nonEmptyString(frontmatter.get("publicCollection"));
    String contentType = nonEmptyString(frontmatter.get("publicContentType"));
    boolean published = Boolean.TRUE.equals(frontmatter.get("publish"));
    boolean validCollection = collection != null && PublicationKind.allowedCollections().contains(collection);
    boolean validContentType = validCollection && contentType != null
        && PublicationKind.allowedContentTypes(collection).contains(contentType);

    if (!published) {
      diagnostics.add(new PublicationDiagnostic("publish", "must be true; allowed value: true"));
    } else {
      String publicId = nonEmptyString(frontmatter.get("publicId"));
      if (publicId == null || !PUBLIC_ID.matcher(publicId).matches()) {
        diagnostics.add(new PublicationDiagnostic("publicId", "must be a lowercase route slug"));
      }
      if (!validCollection) {
        diagnostics.add(new PublicationDiagnostic("publicCollection", "must be one of: " + values(PublicationKind.allowedCollections())));
        diagnostics.add(new PublicationDiagnostic("publicContentType", "requires a valid publicCollection to determine allowed values"));
      } else if (!validContentType) {
        diagnostics.add(new PublicationDiagnostic("publicContentType", "must be one of: " + values(PublicationKind.allowedContentTypes(collection))));
      }
    }

    if (validContentType) {
      if (published) {
        for (PublicationRequirement requirement : PublicationKind.requirementsFor(collection, contentType)) {
          if (requirement.source().equals("frontmatter") && !identity(requirement.validator())
              && !hasValidFrontmatterValue(frontmatter, requirement)) {
            diagnostics.add(new PublicationDiagnostic(String.join(" / ", requirement.fields()), requirement.validator() == RequirementValidator.EDITORIAL_PAGE
                ? "must be one of: " + values(EDITORIAL_PAGES) : requirement.expectation()));
          }
        }
      }
      for (PublicationRequirement requirement : PublicationKind.requirementsFor(collection, contentType)) {
        if (requirement.source().equals("body") && requirement.validator() == RequirementValidator.BODY_SECTION
            && !hasVisibleSection(body, requirement.fields().getFirst())) {
          diagnostics.add(new PublicationDiagnostic(requirement.fields().getFirst(), requirement.expectation()));
        }
      }
    }
    return List.copyOf(diagnostics);
  }

  private static boolean identity(RequirementValidator validator) {
    return switch (validator) {
      case REQUIRED_TRUE, ROUTE_SLUG, COLLECTION, CONTENT_TYPE -> true;
      default -> false;
    };
  }

  private static boolean hasValidFrontmatterValue(Map<String, Object> frontmatter, PublicationRequirement requirement) {
    return requirement.fields().stream().anyMatch(field -> switch (requirement.validator()) {
      case NON_EMPTY_STRING, ONE_NON_EMPTY_STRING -> nonEmptyString(frontmatter.get(field)) != null;
      case ONE_NON_EMPTY_STRING_OR_LIST -> nonEmptyStringOrList(frontmatter.get(field));
      case EDITORIAL_PAGE -> frontmatter.get(field) instanceof String value && EDITORIAL_PAGES.contains(value);
      default -> false;
    });
  }

  private static boolean nonEmptyStringOrList(Object value) {
    if (nonEmptyString(value) != null) return true;
    return value instanceof List<?> items && items.stream().anyMatch(item -> nonEmptyString(item) != null);
  }

  private static String nonEmptyString(Object value) {
    if (!(value instanceof String text)) return null;
    String stripped = text.strip();
    return stripped.isEmpty() ? null : stripped;
  }

  private static String values(Set<String> values) {
    return values.stream().sorted().collect(java.util.stream.Collectors.joining(", "));
  }

  private static boolean hasVisibleSection(String body, String heading) {
    String searchable = maskProtectedContexts(body);
    Pattern section = Pattern.compile("(?ms)^##[ \\t]+" + Pattern.quote(heading)
        + "[ \\t]*(?:\\r?\\n|\\z)(.*?)(?=^##[ \\t]+[^\\r\\n]*(?:\\r?\\n|\\z)|\\z)");
    var match = section.matcher(searchable);
    if (!match.find()) return false;
    String content = body.substring(match.start(1), match.end(1));
    return !content.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)%%.*?%%", "").strip().isEmpty();
  }

  private static String maskProtectedContexts(String body) {
    StringBuilder result = new StringBuilder(body.length());
    int cursor = 0;
    while (cursor < body.length()) {
      ProtectedSpan span = nextProtectedSpan(body, cursor);
      if (span == null) break;
      result.append(body, cursor, span.start());
      result.append(mask(body.substring(span.start(), span.end())));
      cursor = span.end();
    }
    result.append(body, cursor, body.length());
    return result.toString();
  }

  private static ProtectedSpan nextProtectedSpan(String body, int cursor) {
    ProtectedSpan candidate = earliest(
        fencedSpan(body, cursor), patternSpan(HTML_COMMENT, body, cursor), rawHtmlPreSpan(body, cursor),
        patternSpan(INLINE_CODE, body, cursor));
    int obsidianStart = body.indexOf("%%", cursor);
    if (obsidianStart >= 0 && (candidate == null || obsidianStart < candidate.start())) {
      int closing = body.indexOf("%%", obsidianStart + 2);
      return new ProtectedSpan(obsidianStart, closing < 0 ? body.length() : closing + 2);
    }
    return candidate;
  }

  private static ProtectedSpan fencedSpan(String body, int cursor) {
    var opening = FENCE_OPEN.matcher(body);
    while (opening.find(cursor)) {
      String fence = opening.group(1);
      if (fence.charAt(0) == '`' && opening.group(2).contains("`")) {
        cursor = opening.end();
        continue;
      }
      var closing = FENCE_CLOSE.matcher(body);
      while (closing.find(opening.end())) {
        String closingFence = closing.group(1);
        if (closingFence.charAt(0) == fence.charAt(0) && closingFence.length() >= fence.length()) {
          return new ProtectedSpan(opening.start(), closing.end());
        }
      }
      return new ProtectedSpan(opening.start(), body.length());
    }
    return null;
  }

  private static ProtectedSpan rawHtmlPreSpan(String body, int cursor) {
    var opening = RAW_HTML_PRE_OPEN.matcher(body);
    if (!opening.find(cursor)) return null;
    var closing = RAW_HTML_PRE_CLOSE.matcher(body);
    int end = closing.find(opening.end()) ? closing.end() : body.length();
    return new ProtectedSpan(opening.start(), end);
  }

  private static ProtectedSpan patternSpan(Pattern pattern, String body, int cursor) {
    var match = pattern.matcher(body);
    return match.find(cursor) ? new ProtectedSpan(match.start(), match.end()) : null;
  }

  private static ProtectedSpan earliest(ProtectedSpan... spans) {
    ProtectedSpan earliest = null;
    for (ProtectedSpan span : spans) {
      if (span != null && (earliest == null || span.start() < earliest.start())) earliest = span;
    }
    return earliest;
  }

  private static String mask(String value) {
    return value.replaceAll("[^\\r\\n]", " ");
  }

  private record ProtectedSpan(int start, int end) { }
}
