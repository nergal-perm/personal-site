package dev.eugene.astroexport.review;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.eugene.astroexport.model.ManifestEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** On-disk cache of normalized Russian public records. */
public final class RuCache {
  public static final int SCHEMA_VERSION = 1;
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  private RuCache() { }

  public static NormalizedPublicRecord recordFromEntry(ManifestEntry entry) {
    String collection = collection(entry);
    String publicId = string(entry.metadata().get("id"));
    return new NormalizedPublicRecord(
        SCHEMA_VERSION,
        collection,
        publicId,
        contentType(entry.metadata()),
        entry.sourcePath(),
        entry.targetPath(),
        entry.route(),
        deepMap(entry.metadata()),
        entry.body(),
        string(entry.metadata().get("sourceHash")),
        entry.translationSourceHash() == null
            ? string(entry.metadata().get("sourceHash"))
            : entry.translationSourceHash(),
        entry.translationSourceMetadata());
  }

  public static List<Path> writeCachedRecords(
      Path cacheRoot,
      List<NormalizedPublicRecord> records) {
    Path parent = cacheRoot.toAbsolutePath().getParent();
    try {
      Files.createDirectories(parent);
      Path staging = Files.createTempDirectory(parent, "." + cacheRoot.getFileName() + "-stage-");
      Path backup = null;
      try {
        for (NormalizedPublicRecord record : records) {
          Path path = recordPath(staging, record);
          Files.createDirectories(path.getParent());
          Files.writeString(path, JSON.writeValueAsString(record) + "\n");
        }
        if (Files.exists(cacheRoot)) {
          backup = Files.createTempDirectory(parent, "." + cacheRoot.getFileName() + "-backup-");
          Files.delete(backup);
          Files.move(cacheRoot, backup);
        }
        try {
          Files.move(staging, cacheRoot);
        } catch (IOException error) {
          if (backup != null && Files.exists(backup) && !Files.exists(cacheRoot)) {
            Files.move(backup, cacheRoot);
          }
          throw error;
        }
        if (backup != null) {
          deleteTree(backup);
        }
      } catch (RuntimeException | IOException error) {
        if (Files.exists(staging)) {
          deleteTree(staging);
        }
        throw error;
      }
      return records.stream().map(record -> recordPath(cacheRoot, record)).toList();
    } catch (IOException error) {
      throw new IllegalStateException("cannot write RU cache " + cacheRoot, error);
    }
  }

  public static Map<CacheKey, NormalizedPublicRecord> loadCachedRecords(Path cacheRoot) {
    LinkedHashMap<CacheKey, NormalizedPublicRecord> records = new LinkedHashMap<>();
    if (!Files.exists(cacheRoot)) {
      return records;
    }
    try (var paths = Files.walk(cacheRoot, 2)) {
      for (Path path : paths
          .filter(Files::isRegularFile)
          .filter(value -> value.getFileName().toString().endsWith(".json"))
          .sorted()
          .toList()) {
        NormalizedPublicRecord record = JSON.readValue(
            Files.readString(path), new TypeReference<NormalizedPublicRecord>() { });
        records.put(new CacheKey(record.collection(), record.publicId()), record);
      }
      return records;
    } catch (IOException error) {
      throw new IllegalStateException("cannot load RU cache " + cacheRoot, error);
    }
  }

  public static List<NormalizedPublicRecord> changedRecords(
      Path cacheRoot,
      List<ManifestEntry> entries) {
    Map<CacheKey, NormalizedPublicRecord> previous = loadCachedRecords(cacheRoot);
    List<NormalizedPublicRecord> changed = new ArrayList<>();
    for (ManifestEntry entry : entries) {
      NormalizedPublicRecord current = recordFromEntry(entry);
      CacheKey key = new CacheKey(current.collection(), current.publicId());
      if (!current.equals(previous.get(key))) {
        changed.add(current);
      }
    }
    return List.copyOf(changed);
  }

  private static Path recordPath(Path root, NormalizedPublicRecord record) {
    return root.resolve(record.collection()).resolve(record.publicId() + ".json");
  }

  private static String collection(ManifestEntry entry) {
    String[] parts = entry.targetPath().split("/");
    if (parts.length == 5
        && "src".equals(parts[0])
        && "content".equals(parts[1])
        && "ru".equals(parts[3])) {
      return parts[2];
    }
    if (parts.length == 5
        && "src".equals(parts[0])
        && "data".equals(parts[1])
        && "pages".equals(parts[2])
        && "ru".equals(parts[3])) {
      return "editorial";
    }
    throw new IllegalArgumentException("unsupported target path " + entry.targetPath());
  }

  private static String contentType(Map<String, Object> metadata) {
    for (String key : List.of("contentType", "reviewType", "type")) {
      String value = string(metadata.get(key));
      if (!value.isEmpty()) {
        return value;
      }
    }
    return null;
  }

  private static String string(Object value) {
    return value == null ? "" : value.toString();
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
      return list.stream().map(RuCache::deepCopy).toList();
    }
    return value;
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  public record CacheKey(String collection, String publicId) { }

  public record NormalizedPublicRecord(
      @JsonProperty("schema_version") int schemaVersion,
      String collection,
      @JsonProperty("public_id") String publicId,
      @JsonProperty("content_type") String contentType,
      @JsonProperty("source_path") String sourcePath,
      @JsonProperty("target_path") String targetPath,
      String route,
      Map<String, Object> metadata,
      String body,
      @JsonProperty("source_hash") String sourceHash,
      @JsonProperty("translation_source_hash") String translationSourceHash,
      @JsonProperty("translation_source_metadata")
      Map<String, Object> translationSourceMetadata) {
    public NormalizedPublicRecord {
      metadata = deepMap(metadata);
      translationSourceMetadata =
          translationSourceMetadata == null ? null : deepMap(translationSourceMetadata);
    }
  }
}
