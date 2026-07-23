package dev.eugene.astroexport.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eugene.astroexport.model.ManifestEntry;
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
  void detectsBodyChangesAgainstCacheRoot() {
    RuCache.writeCachedRecords(
        temp.resolve("cache"),
        List.of(RuCache.recordFromEntry(entry("Старый текст."))));

    assertEquals(
        List.of(RuCache.recordFromEntry(entry("Новый текст."))),
        RuCache.changedRecords(temp.resolve("cache"), List.of(entry("Новый текст."))));
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
}
