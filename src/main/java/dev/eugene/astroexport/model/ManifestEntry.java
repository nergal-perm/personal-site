package dev.eugene.astroexport.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** A normalized public artifact before it is written into the Astro checkout. */
public record ManifestEntry(String sourcePath, String targetPath, String route, Map<String, Object> metadata,
                            String body) {
  public ManifestEntry {
    metadata = new LinkedHashMap<>(metadata);
  }
}
