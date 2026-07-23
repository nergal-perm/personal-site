package dev.eugene.astroexport.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eugene.astroexport.model.Note;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PublicationValidatorTest {
  private final PublicationValidator validator = new PublicationValidator();

  @Test
  void reportsConceptRequirementsInSchemaOrder() {
    assertEquals(List.of(
        diagnostic("description", "must be a non-empty string"),
        diagnostic("Определение", "must be a non-empty section")),
        validator.validate(note(Map.of(
            "publish", true, "publicId", "organisation", "publicCollection", "concepts",
            "publicContentType", "concept"), "")));
  }

  @Test
  void acceptsVisibleDefinitionButRejectsHeadingInsideFencedCode() {
    Map<String, Object> frontmatter = Map.of(
        "publish", true, "publicId", "organisation", "publicCollection", "concepts",
        "publicContentType", "concept", "description", "Public description");

    assertEquals(List.of(), validator.validate(note(frontmatter, "## Определение\n\nVisible.\n")));
    assertEquals(List.of(diagnostic("Определение", "must be a non-empty section")),
        validator.validate(note(frontmatter, "```markdown\n## Определение\n\nHidden.\n```\n")));
  }

  @Test
  void rejectsProtectedHeadingsAndNonVisibleDefinitionContent() {
    Map<String, Object> frontmatter = conceptFrontmatter();

    for (String body : List.of(
        "`Code sample.\n## Определение\n\nNot a rendered section.\n`\n",
        "<pre>\n## Определение\n\nNot a rendered section.\n</pre>\n",
        "## Определение\n\n<!-- Hidden definition. -->\n",
        "## Определение\n\n%% Hidden definition. %%\n",
        "##\nОпределение\n\nNot a rendered section.\n",
        "<!--\n## Определение\n\nHidden.\n-->\n",
        "%%\n## Определение\n\nHidden.\n%%\n")) {
      assertEquals(List.of(diagnostic("Определение", "must be a non-empty section")),
          validator.validate(note(frontmatter, body)), body);
    }
  }

  @Test
  void acceptsVisibleInlineCodeAndRawPreInsideARealDefinitionSection() {
    for (String definition : List.of(
        "`Code sample.\n## Not a boundary\n\nStill inline code.\n`",
        "<pre>\n## Not a boundary\n\nStill raw HTML.\n</pre>")) {
      assertEquals(List.of(), validator.validate(note(conceptFrontmatter(), "## Определение\n\n" + definition + "\n")));
    }
  }

  @Test
  void reportsPublishAndResolvableBodyDiagnosticsWhenPublishIsInvalid() {
    assertEquals(List.of(
        diagnostic("publish", "must be true; allowed value: true"),
        diagnostic("Определение", "must be a non-empty section")),
        validator.validate(note(Map.of(
            "publish", false, "publicId", "organisation", "publicCollection", "concepts",
            "publicContentType", "concept", "description", "Public description"), "")));
  }

  @Test
  void requiresBooleanTrueForPublishAndDoesNotGuessInvalidKinds() {
    for (Object publish : List.of(false, "true", 1)) {
      assertEquals(List.of(diagnostic("publish", "must be true; allowed value: true")),
          validator.validate(note(Map.of("publish", publish), "")));
    }
    assertEquals(List.of(
        diagnostic("publicCollection", "must be one of: bibliography, blog, concepts, editorial, music"),
        diagnostic("publicContentType", "requires a valid publicCollection to determine allowed values")),
        validator.validate(note(Map.of("publish", true, "publicId", "invalid-collection",
            "publicCollection", "unknown", "publicContentType", "concept"), "")));
    assertEquals(List.of(diagnostic("publicContentType", "must be one of: concept")),
        validator.validate(note(Map.of("publish", true, "publicId", "missing-type",
            "publicCollection", "concepts"), "")));
  }

  @Test
  void listsSupportedCollectionsAndDefersTypeWhenCollectionIsInvalid() {
    assertEquals(List.of(
        diagnostic("publicCollection", "must be one of: bibliography, blog, concepts, editorial, music"),
        diagnostic("publicContentType", "requires a valid publicCollection to determine allowed values")),
        validator.validate(note(Map.of("publish", true, "publicId", "missing-collection",
            "publicContentType", "concept"), "")));
  }

  @Test
  void validatesAlternativesAndEditorialPagesWithoutTrimmingTheirValues() {
    assertEquals(List.of(), validator.validate(note(Map.of(
        "publish", true, "publicId", "claim", "publicCollection", "blog",
        "publicContentType", "claim", "statement", 123, "description", "Valid claim"), "Body")));
    assertEquals(List.of(diagnostic("editorialPage",
        "must be one of: about, claims, concepts, essays, home, library, music, notes, now")),
        validator.validate(note(Map.of(
            "publish", true, "publicId", "home", "publicCollection", "editorial",
            "publicContentType", "curated_page", "editorialPage", " home "), "Body")));
    assertEquals(List.of(diagnostic("editorialPage",
        "must be one of: about, claims, concepts, essays, home, library, music, notes, now")),
        validator.validate(note(Map.of("publish", true, "publicId", "home", "publicCollection", "editorial",
            "publicContentType", "curated_page"), "Body")));
  }

  @Test
  void validatesNonEmptyAuthorLists() {
    assertEquals(List.of(diagnostic("authors / author", "must contain at least one non-empty string")),
        validator.validate(note(Map.of(
            "publish", true, "publicId", "book", "publicCollection", "bibliography",
            "publicContentType", "book", "authors", List.of(123)), "Body")));
  }

  @Test
  void validatesEveryDeclaredPublicationKindWithCompleteFixtures() {
    for (var kind : dev.eugene.astroexport.model.PublicationKind.all()) {
      assertEquals(List.of(), validator.validate(note(validFrontmatter(kind.collection(), kind.contentType()),
          validBody(kind.collection(), kind.contentType()))), kind.collection() + "/" + kind.contentType());
    }
  }

  @Test
  void reportsOneViolationForMissingAlternativeFields() {
    assertEquals(List.of(diagnostic("statement / description", "must be a non-empty string")),
        validator.validate(note(Map.of("publish", true, "publicId", "claim", "publicCollection", "blog",
            "publicContentType", "claim"), "Body")));
    assertEquals(List.of(diagnostic("work / albumTitle", "must be a non-empty string")),
        validator.validate(note(Map.of("publish", true, "publicId", "album", "publicCollection", "music",
            "publicContentType", "album", "artist", "Artist"), "## Контекст записи\n\nText\n\n## Личная связь\n\nText\n")));
    assertEquals(List.of(diagnostic("authors / author", "must contain at least one non-empty string")),
        validator.validate(note(Map.of("publish", true, "publicId", "book", "publicCollection", "bibliography",
            "publicContentType", "book"), "Body")));
  }

  @Test
  void acceptsValidAlternativeFieldsAndDoesNotTreatDefinitionFrontmatterAsTheBodySection() {
    assertEquals(List.of(), validator.validate(note(Map.of("publish", true, "publicId", "book",
        "publicCollection", "bibliography", "publicContentType", "book", "authors", List.of(123),
        "author", "Valid author"), "Body")));
    assertEquals(List.of(), validator.validate(note(Map.of("publish", true, "publicId", "album",
        "publicCollection", "music", "publicContentType", "album", "artist", "Artist",
        "work", 123, "albumTitle", "Album"), "## Контекст записи\n\nText\n\n## Личная связь\n\nText\n")));
    assertEquals(List.of(diagnostic("Определение", "must be a non-empty section")),
        validator.validate(note(Map.of("publish", true, "publicId", "organisation", "publicCollection", "concepts",
            "publicContentType", "concept", "description", "Public description", "definition", "Legacy value"), "")));
  }

  private static Note note(Map<String, Object> frontmatter, String body) {
    return new Note(Path.of("Note.md"), "notes/Note.md", "Note", frontmatter, body, true,
        "", "", "", List.of());
  }

  private static Map<String, Object> conceptFrontmatter() {
    return Map.of("publish", true, "publicId", "organisation", "publicCollection", "concepts",
        "publicContentType", "concept", "description", "Public description");
  }

  private static Map<String, Object> validFrontmatter(String collection, String contentType) {
    var frontmatter = new java.util.LinkedHashMap<String, Object>();
    frontmatter.put("publish", true);
    frontmatter.put("publicId", collection + "-" + contentType.replace('_', '-'));
    frontmatter.put("publicCollection", collection);
    frontmatter.put("publicContentType", contentType);
    switch (collection + "/" + contentType) {
      case "blog/claim" -> frontmatter.put("statement", "A public claim.");
      case "bibliography/book" -> frontmatter.put("authors", List.of("An author"));
      case "music/album" -> { frontmatter.put("artist", "An artist"); frontmatter.put("work", "An album"); }
      case "concepts/concept" -> frontmatter.put("description", "A public description.");
      case "editorial/curated_page" -> frontmatter.put("editorialPage", "home");
      default -> { }
    }
    return frontmatter;
  }

  private static String validBody(String collection, String contentType) {
    return switch (collection + "/" + contentType) {
      case "music/album" -> "## Контекст записи\n\nContext.\n\n## Личная связь\n\nAssociation.\n";
      case "concepts/concept" -> "## Определение\n\nA public definition.\n";
      default -> "Body";
    };
  }

  private static PublicationDiagnostic diagnostic(String field, String message) {
    return new PublicationDiagnostic(field, message);
  }
}
