package dev.eugene.astroexport.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A normalized public artifact before it is written into the Astro checkout. */
public record ManifestEntry(String sourcePath, String targetPath, String route, Map<String, Object> metadata,
                            String body, String translationSourceHash,
                            Map<String, Object> translationSourceMetadata) {
  public ManifestEntry(
      String sourcePath,
      String targetPath,
      String route,
      Map<String, Object> metadata,
      String body) {
    this(sourcePath, targetPath, route, metadata, body, null, null);
  }

  public ManifestEntry {
    metadata = deepMap(metadata);
    translationSourceMetadata =
        translationSourceMetadata == null ? null : deepMap(translationSourceMetadata);
  }

  private static Map<String, Object> deepMap(Map<String, Object> source) {
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      copy.put(entry.getKey(), deepCopy(entry.getValue()));
    }
    return copy;
  }

  private static Object deepCopy(Object value) {
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
      }
      return copy;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(ManifestEntry::deepCopy).toList();
    }
    return value;
  }
}
