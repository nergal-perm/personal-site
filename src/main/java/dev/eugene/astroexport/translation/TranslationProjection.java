package dev.eugene.astroexport.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.model.ManifestEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects the Russian values that are allowed to make an English review stale. */
public final class TranslationProjection {
  public static final Set<String> INVARIANT_KEYS = Set.of(
      "id",
      "publish",
      "language",
      "sourceLanguage",
      "translationOf",
      "date",
      "updated",
      "topics",
      "links",
      "route",
      "target",
      "sourceHash",
      "status",
      "foundational",
      "readTime",
      "cover",
      "telegram",
      "tags",
      "aliases",
      "translationStatus",
      "translatedAt",
      "translationProfile",
      "contentType",
      "reviewType",
      "artist",
      "work",
      "claimKinds",
      "attestation",
      "confidence",
      "releaseDate",
      "genreTags",
      "streamingUrl",
      "bandcampEmbedUrl",
      "publicationDate",
      "start",
      "end",
      "authors",
      "type",
      "searchable",
      "featured",
      "primary",
      "selected",
      "items",
      "pinned",
      "number",
      "key",
      "layout",
      "kind");

  private static final Set<String> SOURCE_CONTROL_KEYS = Set.of(
      "translationStatus", "translatedAt", "translationProfile");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Object OMIT = new Object();

  private TranslationProjection() { }

  public static String translationSourceHash(ManifestEntry entry) {
    boolean includeBody = collection(entry).matches("blog|bibliography|concepts");
    LinkedHashMap<String, Object> value = new LinkedHashMap<>();
    value.put("bodyIncluded", includeBody);
    value.put("metadata", translatableProjection(entry.metadata()));
    String body = includeBody ? entry.body() : "";
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(
          (pythonJson(value) + "\n" + body).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException("cannot compute translation source hash", error);
    }
  }

  public static Map<String, Object> translatableProjection(Map<String, Object> metadata) {
    @SuppressWarnings("unchecked")
    Map<String, Object> projected = (Map<String, Object>) project(metadata, null);
    return projected;
  }

  public static boolean hasTranslationLeaf(Object value, String key) {
    if (isTextToken(value)) {
      return true;
    }
    if (isReferenceToken(value) || isInvariant(key)) {
      return false;
    }
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream().anyMatch(
          entry -> hasTranslationLeaf(entry.getValue(), String.valueOf(entry.getKey())));
    }
    if (value instanceof List<?> list) {
      return list.stream().anyMatch(item -> hasTranslationLeaf(item, null));
    }
    return true;
  }

  public static boolean isTextToken(Object value) {
    return value instanceof Map<?, ?> map
        && map.size() == 2
        && "text".equals(map.get("kind"))
        && map.get("value") instanceof String;
  }

  public static boolean isReferenceToken(Object value) {
    return value instanceof Map<?, ?> map
        && map.size() == 2
        && "reference".equals(map.get("kind"))
        && map.get("target") instanceof String;
  }

  private static Object project(Object value, String key) {
    if (isInvariant(key)) {
      return OMIT;
    }
    if (isTextToken(value) || isReferenceToken(value)) {
      return deepCopy(value);
    }
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String childKey = String.valueOf(entry.getKey());
        Object child = project(entry.getValue(), childKey);
        if (child != OMIT) {
          result.put(childKey, child);
        }
      }
      return result;
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>();
      for (Object item : list) {
        Object child = project(item, null);
        if (child != OMIT) {
          result.add(child);
        }
      }
      return result;
    }
    return value;
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
      return list.stream().map(TranslationProjection::deepCopy).toList();
    }
    return value;
  }

  private static boolean isInvariant(String key) {
    return key != null && (INVARIANT_KEYS.contains(key) || SOURCE_CONTROL_KEYS.contains(key));
  }

  private static String collection(ManifestEntry entry) {
    String[] parts = entry.targetPath().split("/");
    if (parts.length == 5
        && "src".equals(parts[0])
        && "content".equals(parts[1])
        && "ru".equals(parts[3])) {
      return parts[2];
    }
    if (entry.targetPath().startsWith("src/data/pages/ru/")) {
      return "editorial";
    }
    throw new IllegalArgumentException("unsupported RU target path " + entry.targetPath());
  }

  private static String pythonJson(Object value) throws Exception {
    if (value == null
        || value instanceof String
        || value instanceof Character
        || value instanceof Boolean) {
      return JSON.writeValueAsString(value);
    }
    if (value instanceof Number) {
      return value.toString();
    }
    if (value instanceof Map<?, ?> map) {
      List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
      entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
      List<String> serialized = new ArrayList<>();
      for (Map.Entry<?, ?> entry : entries) {
        serialized.add(
            JSON.writeValueAsString(String.valueOf(entry.getKey()))
                + ": "
                + pythonJson(entry.getValue()));
      }
      return "{" + String.join(", ", serialized) + "}";
    }
    if (value instanceof List<?> list) {
      List<String> serialized = new ArrayList<>();
      for (Object item : list) {
        serialized.add(pythonJson(item));
      }
      return "[" + String.join(", ", serialized) + "]";
    }
    return JSON.writeValueAsString(value.toString());
  }
}
