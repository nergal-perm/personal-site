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
  void stopsAfterPublishViolation() {
    assertEquals(List.of(diagnostic("publish", "must be true; allowed value: true")),
        validator.validate(note(Map.of(), "")));
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
        "publicContentType", "claim", "description", "Valid claim"), "Body")));
    assertEquals(List.of(diagnostic("editorialPage",
        "must be one of: about, claims, concepts, essays, home, library, music, notes, now")),
        validator.validate(note(Map.of(
            "publish", true, "publicId", "home", "publicCollection", "editorial",
            "publicContentType", "curated_page", "editorialPage", " home "), "Body")));
  }

  @Test
  void validatesNonEmptyAuthorLists() {
    assertEquals(List.of(diagnostic("authors / author", "must contain at least one non-empty string")),
        validator.validate(note(Map.of(
            "publish", true, "publicId", "book", "publicCollection", "bibliography",
            "publicContentType", "book", "authors", List.of(123)), "Body")));
  }

  private static Note note(Map<String, Object> frontmatter, String body) {
    return new Note(Path.of("Note.md"), "notes/Note.md", "Note", frontmatter, body, true,
        "", "", "", List.of());
  }

  private static PublicationDiagnostic diagnostic(String field, String message) {
    return new PublicationDiagnostic(field, message);
  }
}
