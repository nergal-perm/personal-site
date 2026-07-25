package dev.eugene.astroexport.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.model.PublicationKind;
import dev.eugene.astroexport.model.PublicationRequirement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PublicationContractTest {
  @Test
  void declaresExactlyTheSupportedPublicationPairs() {
    assertEquals(Set.of(
        "blog/essay", "blog/claim", "blog/note", "bibliography/book", "music/album",
        "concepts/concept", "editorial/curated_page"),
        PublicationKind.all().stream().map(kind -> kind.collection() + "/" + kind.contentType())
            .collect(java.util.stream.Collectors.toSet()));
    for (PublicationKind kind : PublicationKind.all()) {
      assertEquals(kind.requirements(), PublicationKind.requirementsFor(kind.collection(), kind.contentType()));
    }
    assertTrue(PublicationKind.requirementsFor("blog", "case").isEmpty());
  }

  @Test
  void usesTheTaskFourValidatorVocabulary() {
    assertEquals(Set.of(
        RequirementValidator.BOOLEAN_TRUE,
        RequirementValidator.NON_EMPTY_STRING,
        RequirementValidator.NON_EMPTY_STRING_OR_LIST,
        RequirementValidator.SUPPORTED_COLLECTION,
        RequirementValidator.SUPPORTED_CONTENT_TYPE,
        RequirementValidator.SUPPORTED_EDITORIAL_PAGE,
        RequirementValidator.CONCEPT_DEFINITION_SECTION),
        PublicationKind.all().stream()
            .flatMap(kind -> kind.requirements().stream())
            .map(requirement -> requirement.validator())
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void conceptRequiresDescriptionAndDefinitionBodySectionInOrder() {
    var requirements = PublicationKind.requirementsFor("concepts", "concept");

    assertEquals("description", requirements.get(requirements.size() - 2).fields().getFirst());
    assertEquals(RequirementValidator.NON_EMPTY_STRING,
        requirements.get(requirements.size() - 2).validator());
    assertEquals("Определение", requirements.getLast().fields().getFirst());
    assertEquals(RequirementValidator.CONCEPT_DEFINITION_SECTION, requirements.getLast().validator());
  }

  @Test
  void rejectsAlternativeFrontmatterFieldNamesEncodedAsText() {
    var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> new PublicationRequirement(List.of("authors or author"), "expectation", "author expectation",
            "frontmatter", RequirementValidator.NON_EMPTY_STRING));

    assertEquals("alternative frontmatter fields must be separate schema entries", error.getMessage());
  }
}
