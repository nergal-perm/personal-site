package dev.eugene.astroexport.translation;

import java.util.LinkedHashMap;
import java.util.Map;

/** The durable English review payload after frontmatter normalization. */
public record TranslationPatch(
    String sourceHash,
    String translationStatus,
    String translatedAt,
    String translationProfile,
    Map<String, Object> metadata,
    Map<String, Object> referenceTranslations,
    String body,
    String origin) {
  public TranslationPatch {
    metadata = new LinkedHashMap<>(metadata);
    referenceTranslations = new LinkedHashMap<>(referenceTranslations);
  }
}
