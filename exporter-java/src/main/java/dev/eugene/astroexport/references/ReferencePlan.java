package dev.eugene.astroexport.references;

import java.util.List;
import java.util.Map;

/** Immutable schema-side plan for semantic reference projection. */
public record ReferencePlan(
    String pageRef,
    String sourcePath,
    List<String> order,
    Map<String, PageReferenceMap.Reference> references) {

  public ReferencePlan {
    order = List.copyOf(order);
    references = Map.copyOf(references);
  }
}
