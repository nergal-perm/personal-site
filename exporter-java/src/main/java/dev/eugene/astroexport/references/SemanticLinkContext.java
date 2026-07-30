package dev.eugene.astroexport.references;

import java.util.Map;
import java.util.Optional;

public record SemanticLinkContext(
    VaultReferenceCatalog catalog,
    VaultReferenceResolver resolver,
    Map<String, Optional<PageReferenceMap>> previousApprovedMaps) {

  public SemanticLinkContext {
    previousApprovedMaps = Map.copyOf(previousApprovedMaps);
  }

  public String pageRef(String sourcePath) {
    return catalog.requireByCurrentPath(sourcePath).pageRef();
  }

  public Optional<PageReferenceMap> previousApprovedMap(String sourcePath) {
    return previousApprovedMaps.getOrDefault(sourcePath, Optional.empty());
  }
}
