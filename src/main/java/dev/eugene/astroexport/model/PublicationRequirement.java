package dev.eugene.astroexport.model;

import dev.eugene.astroexport.validation.RequirementValidator;
import java.util.List;

public record PublicationRequirement(
    List<String> fields,
    String expectation,
    String authorExpectation,
    String source,
    RequirementValidator validator) {
  public PublicationRequirement {
    fields = List.copyOf(fields);
    if (fields.isEmpty() || fields.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException("publication requirement fields must be non-empty");
    }
    if (fields.stream().distinct().count() != fields.size()) {
      throw new IllegalArgumentException("publication requirement fields must be unique");
    }
    if (source.equals("frontmatter") && fields.stream().anyMatch(field -> field.contains(" or "))) {
      throw new IllegalArgumentException("alternative frontmatter fields must be separate schema entries");
    }
  }
}
