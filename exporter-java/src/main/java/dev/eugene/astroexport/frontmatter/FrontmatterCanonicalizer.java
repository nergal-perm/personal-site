package dev.eugene.astroexport.frontmatter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Produces fresh recursively ordered metadata structures for serialization. */
public final class FrontmatterCanonicalizer {
  private FrontmatterCanonicalizer() { }

  public static Map<String, Object> canonicalize(Map<?, ?> source) {
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    source.entrySet().stream()
        .sorted((left, right) -> String.valueOf(left.getKey())
            .compareTo(String.valueOf(right.getKey())))
        .forEachOrdered(entry -> result.put(
            String.valueOf(entry.getKey()),
            canonicalizeValue(entry.getValue())));
    return result;
  }

  private static Object canonicalizeValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return canonicalize(map);
    }
    if (value instanceof List<?> list) {
      return list.stream()
          .map(FrontmatterCanonicalizer::canonicalizeValue)
          .toList();
    }
    return value;
  }
}
