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
    String searchable = MarkdownProtection.mask(body);
    Pattern section = Pattern.compile("(?ms)^##[ \\t]+" + Pattern.quote(heading)
        + "[ \\t]*(?:\\r?\\n|\\z)(.*?)(?=^##[ \\t]+[^\\r\\n]*(?:\\r?\\n|\\z)|\\z)");
    var match = section.matcher(searchable);
    if (!match.find()) return false;
    String content = body.substring(match.start(1), match.end(1));
    return !content.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)%%.*?%%", "").strip().isEmpty();
  }

}
