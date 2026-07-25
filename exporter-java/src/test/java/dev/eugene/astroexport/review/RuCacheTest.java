package dev.eugene.astroexport.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.model.ManifestEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuCacheTest {
  @TempDir
  Path temp;

  @Test
  void roundTripsNormalizedPublicRecord() {
    RuCache.NormalizedPublicRecord record = RuCache.recordFromEntry(entry("Русский текст."));
    List<Path> paths = RuCache.writeCachedRecords(temp.resolve("cache"), List.of(record));

    assertEquals(List.of(temp.resolve("cache/blog/essay.json")), paths);
    assertEquals(
        Map.of(new RuCache.CacheKey("blog", "essay"), record),
        RuCache.loadCachedRecords(temp.resolve("cache")));
  }

  @Test
  void writesExactPythonNormalizedRecordShape() throws Exception {
    ManifestEntry entry = editorialEntry(
        "b".repeat(64),
        Map.of("paths", List.of(Map.of(
            "route", "/ru/essays/dormant/",
            "title", "Скрытый",
            "text", "Скрытый путь."))));
    RuCache.NormalizedPublicRecord record = RuCache.recordFromEntry(entry);
    Path path = RuCache.writeCachedRecords(temp.resolve("cache"), List.of(record)).getFirst();
    Map<String, Object> json = new ObjectMapper().readValue(
        Files.readString(path), new TypeReference<LinkedHashMap<String, Object>>() { });

    assertEquals(List.of(
        "body",
        "collection",
        "content_type",
        "metadata",
        "public_id",
        "route",
        "schema_version",
        "source_hash",
        "source_path",
        "target_path",
        "translation_source_hash",
        "translation_source_metadata"), json.keySet().stream().sorted().toList());
    assertEquals("b".repeat(64), json.get("translation_source_hash"));
    assertEquals(entry.translationSourceMetadata(), json.get("translation_source_metadata"));
    assertEquals(entry.translationSourceMetadata(), record.translationSourceMetadata());
  }

  @Test
  void detectsBodyChangesAgainstCacheRoot() {
    RuCache.writeCachedRecords(
        temp.resolve("cache"),
        List.of(RuCache.recordFromEntry(entry("Старый текст."))));

    assertEquals(
        List.of(RuCache.recordFromEntry(entry("Новый текст."))),
        RuCache.changedRecords(temp.resolve("cache"), List.of(entry("Новый текст."))));
  }

  @Test
  void detectsDormantEditorialAuthoredSourceChange() {
    ManifestEntry before = editorialEntry(
        "b".repeat(64),
        Map.of("paths", List.of(Map.of(
            "route", "/ru/essays/dormant/",
            "title", "Скрытый",
            "text", "Первый скрытый текст."))));
    ManifestEntry after = editorialEntry(
        "c".repeat(64),
        Map.of("paths", List.of(Map.of(
            "route", "/ru/essays/dormant/",
            "title", "Скрытый",
            "text", "Измененный скрытый текст."))));
    RuCache.writeCachedRecords(
        temp.resolve("cache"), List.of(RuCache.recordFromEntry(before)));

    assertEquals(
        List.of(RuCache.recordFromEntry(after)),
        RuCache.changedRecords(temp.resolve("cache"), List.of(after)));
  }

  private static ManifestEntry entry(String body) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "essay");
    metadata.put("title", "Русский заголовок");
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("translationStatus", "source");
    metadata.put("sourceHash", "a".repeat(64));
    return new ManifestEntry(
        "blog/Essay.md",
        "src/content/blog/ru/essay.md",
        "/ru/essays/essay/",
        metadata,
        body);
  }

  private static ManifestEntry editorialEntry(
      String translationSourceHash,
      Map<String, Object> translationSourceMetadata) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "essays");
    metadata.put("title", "Эссе");
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("translationStatus", "source");
    metadata.put("sourceHash", "a".repeat(64));
    metadata.put("paths", List.of());
    return new ManifestEntry(
        "editorial/essays.md",
        "src/data/pages/ru/essays.json",
        "/ru/essays/",
        metadata,
        "",
        translationSourceHash,
        translationSourceMetadata);
  }
}
