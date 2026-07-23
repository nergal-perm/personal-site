package dev.eugene.astroexport.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.eugene.astroexport.model.ManifestEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TranslationProjectionTest {
  @Test
  void projectsOnlyTranslatableLeavesAndReferenceTokens() {
    ManifestEntry entry = entry("Русское тело.");

    assertEquals(
        Map.of(
            "title", "Русский заголовок",
            "cards", List.of(Map.of(
                "title", List.of(Map.of("kind", "reference", "target", "book")),
                "text", List.of(Map.of("kind", "text", "value", "Русский текст."))))),
        TranslationProjection.translatableProjection(entry.metadata()));
  }

  @Test
  void sourceHashChangesForTranslatableMetadataAndPublicBody() {
    ManifestEntry original = entry("Русское тело.");
    LinkedHashMap<String, Object> translatedChange = new LinkedHashMap<>(original.metadata());
    translatedChange.put("title", "Измененный заголовок");
    LinkedHashMap<String, Object> invariantChange = new LinkedHashMap<>(original.metadata());
    invariantChange.put("updated", "2026-07-23");

    assertNotEquals(
        TranslationProjection.translationSourceHash(original),
        TranslationProjection.translationSourceHash(new ManifestEntry(
            original.sourcePath(), original.targetPath(), original.route(), translatedChange, original.body())));
    assertNotEquals(
        TranslationProjection.translationSourceHash(original),
        TranslationProjection.translationSourceHash(entry("Измененное тело.")));
    assertEquals(
        TranslationProjection.translationSourceHash(original),
        TranslationProjection.translationSourceHash(new ManifestEntry(
            original.sourcePath(), original.targetPath(), original.route(), invariantChange, original.body())));
  }

  private static ManifestEntry entry(String body) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "essay");
    metadata.put("title", "Русский заголовок");
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("updated", "2026-07-22");
    metadata.put("route", "/ru/essays/essay/");
    metadata.put("cards", List.of(Map.of(
        "title", List.of(Map.of("kind", "reference", "target", "book")),
        "text", List.of(Map.of("kind", "text", "value", "Русский текст.")))));
    metadata.put("sourceHash", "a".repeat(64));
    return new ManifestEntry(
        "blog/Essay.md",
        "src/content/blog/ru/essay.md",
        "/ru/essays/essay/",
        metadata,
        body);
  }
}
