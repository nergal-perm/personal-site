package dev.eugene.astroexport.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.model.PublicationKind;
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
    assertTrue(PublicationKind.requirementsFor("blog", "case").isEmpty());
  }

  @Test
  void usesTheLivePythonValidatorVocabulary() {
    assertEquals(Set.of(
        RequirementValidator.REQUIRED_TRUE,
        RequirementValidator.ROUTE_SLUG,
        RequirementValidator.COLLECTION,
        RequirementValidator.CONTENT_TYPE,
        RequirementValidator.NON_EMPTY_STRING,
        RequirementValidator.ONE_NON_EMPTY_STRING,
        RequirementValidator.ONE_NON_EMPTY_STRING_OR_LIST,
        RequirementValidator.EDITORIAL_PAGE,
        RequirementValidator.BODY_SECTION,
        RequirementValidator.EDITORIAL_BODY),
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
    assertEquals(RequirementValidator.BODY_SECTION, requirements.getLast().validator());
  }
}
