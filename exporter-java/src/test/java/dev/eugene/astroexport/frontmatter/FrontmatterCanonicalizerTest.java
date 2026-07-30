package dev.eugene.astroexport.frontmatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class FrontmatterCanonicalizerTest {
  @Test
  void canonicalizesEveryMappingWithoutChangingListOrderOrScalars() {
    LinkedHashMap<String, Object> first = new LinkedHashMap<>();
    first.put("z", 2);
    first.put("a", 1);
    LinkedHashMap<String, Object> second = new LinkedHashMap<>();
    second.put("z", 4);
    second.put("a", 3);
    LocalDate date = LocalDate.of(2026, 7, 30);

    LinkedHashMap<String, Object> source = new LinkedHashMap<>();
    source.put("zeta", "last");
    source.put("items", List.of(first, second));
    source.put("date", date);
    source.put("alpha", Map.of("z", "end", "a", "start"));

    Map<String, Object> canonical = FrontmatterCanonicalizer.canonicalize(source);

    assertEquals(List.of("alpha", "date", "items", "zeta"), List.copyOf(canonical.keySet()));
    assertEquals(List.of("a", "z"), List.copyOf(map(canonical.get("alpha")).keySet()));
    List<?> items = (List<?>) canonical.get("items");
    assertEquals(List.of(1, 3), items.stream().map(item -> map(item).get("a")).toList());
    assertEquals(List.of("a", "z"), List.copyOf(map(items.getFirst()).keySet()));
    assertEquals(List.of("a", "z"), List.copyOf(map(items.getLast()).keySet()));
    assertSame(date, canonical.get("date"));
    assertNotSame(source, canonical);
    assertNotSame(first, items.getFirst());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
