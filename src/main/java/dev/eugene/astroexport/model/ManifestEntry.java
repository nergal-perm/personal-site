package dev.eugene.astroexport.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal preflight projection; Task 7 expands this shared model. */
public record ManifestEntry(String sourcePath, String targetPath, String route, Map<String, Object> metadata,
    String body) {
  public ManifestEntry {
    metadata = Map.copyOf(new LinkedHashMap<>(metadata));
  }
}
