package dev.eugene.astroexport.editorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class EditorialParserTest {
  private final EditorialParser parser = new EditorialParser();

  @Test
  void normalizesAllEditorialPageShapes() {
    assertEquals("home", parser.normalize("editorial/home.md", "home", frontmatter("home"), homeBody(), common()).get("type"));
    assertEquals("index", parser.normalize("editorial/essays.md", "essays", frontmatter("essays"), collectionBody("Принцип списка", "Принцип.\n\nПодсказка поиска:: Искать"), common()).get("type"));
    assertEquals("index", parser.normalize("editorial/claims.md", "claims", frontmatter("claims"), base() + "## Витрина\n\n### [[Claim]]\n\nТекст.", common()).get("type"));
    assertEquals(List.of("Рабочие"), parser.normalize("editorial/notes.md", "notes", frontmatter("notes"), base() + "## Три типа заметок\n\n- Рабочие\n\nДействие библиотеки:: Библиотека\nДействие концептов:: Концепты", common()).get("groups"));
    assertEquals("Введение.", parser.normalize("editorial/music.md", "music", frontmatter("music"), base() + "## Введение\n\nВведение.", common()).get("intro"));
    assertEquals(List.of(), parser.normalize("editorial/library.md", "library", frontmatter("library"), base(), common()).get("showcase"));
    assertEquals("Материал", parser.normalize("editorial/concepts.md", "concepts", frontmatter("concepts"), base() + "## Базовый концепт\n\nМетка:: Материал", common()).get("primaryLabel"));
    assertEquals("now", parser.normalize("editorial/now.md", "now", nowFrontmatter(), nowBody(), common()).get("type"));
    assertEquals("Колофон.", parser.normalize("editorial/about.md", "about", frontmatter("about"), base() + "## Лид\n\nЛид.\n\n## Принципы\n\n### Первый\n\nПринцип.\n\n## Колофон\n\nКолофон.", common()).get("colophon"));
  }

  @Test
  void preservesOptionalShowcaseTargetsAndProse() {
    Map<String, Object> metadata = parser.normalize("editorial/essays.md", "essays", frontmatter("essays"), collectionBody("Принцип списка", "Принцип.\n\nПодсказка поиска:: Искать") + "\n\n## Витрина\n\n### [[Essay One]]\n\nНачать с текста.", common());
    assertEquals(List.of(Map.of("target", "Essay One", "text", "Начать с текста.")), metadata.get("showcase"));
    assertEquals(List.of("Essay One"), metadata.get("pinned"));
  }

  @Test
  void reportsExactTargetForMissingEditorialHeading() {
    EditorialParser.ManifestValidationException error = assertThrows(EditorialParser.ManifestValidationException.class,
        () -> parser.normalize("editorial/home.md", "home", frontmatter("home"), base(), common()));
    assertEquals("heroTitle", error.fieldName());
    assertEquals("requires heading `## Hero`", error.reason());
  }

  @Test
  void rejectsMalformedNestedShapesAndNonBooleanSearchable() {
    EditorialParser.ManifestValidationException nested = assertThrows(EditorialParser.ManifestValidationException.class,
        () -> parser.normalize("editorial/home.md", "home", frontmatter("home"), base() + "## Hero\n\n### Заголовок\n\nЗаголовок\n\n### Лид\n\nЛид\n\n### Описание изображения\n\nAlt\n\n## Сейчас\n\n### Изучаю\n\nТолько одна строка", common()));
    assertEquals("current", nested.fieldName());
    Map<String, Object> bad = frontmatter("library");
    bad.put("publicSearchable", "true");
    EditorialParser.ManifestValidationException searchable = assertThrows(EditorialParser.ManifestValidationException.class,
        () -> parser.normalize("editorial/library.md", "library", bad, base(), common()));
    assertEquals("searchable", searchable.fieldName());
  }

  @Test
  void keepsRawWikilinkIdentityInNowStructuralHeadings() {
    Map<String, Object> metadata = parser.normalize("editorial/now.md", "now", nowFrontmatter(), nowBody().replace("### Текущий материал", "### [[Книга]]"), common());
    assertEquals("[[Книга]]", ((List<Map<String, Object>>) metadata.get("sections")).getFirst().get("title"));
    assertFalse(metadata.containsKey("showcase"));
  }

  private static Map<String, Object> common() { return new LinkedHashMap<>(Map.of("id", "page", "title", "Страница", "topics", List.of(), "links", List.of())); }
  private static Map<String, Object> frontmatter(String page) { Map<String, Object> map = new LinkedHashMap<>(); map.put("editorialPage", page); return map; }
  private static Map<String, Object> nowFrontmatter() { Map<String, Object> map = frontmatter("now"); map.put("date", "2026-07-15"); map.put("status", "current"); return map; }
  private static String base() { return "## Кратко\n\nКратко.\n\n## Eyebrow\n\nБровь.\n\n"; }
  private static String collectionBody(String heading, String content) { return base() + "## " + heading + "\n\n" + content; }
  private static String homeBody() { return base() + "## Hero\n\n### Заголовок\n\nЗаголовок.\n\n### Лид\n\nЛид.\n\n### Описание изображения\n\nAlt.\n\n## Сейчас\n\n### Изучаю\n\nТема\n\nОписание\n\n### Создаю\n\nПроект\n\nОписание\n\n### Читаю\n\nКнига\n\nОписание\n\n### Слушаю\n\nАльбом\n\nОписание"; }
  private static String nowBody() { return base() + "## Обновлено\n\nСегодня.\n\n## Читаю\n\n### Текущий материал\n\nТекст.\n\nДействие вопроса:: Вопрос\nДействие записи:: Запись"; }
}
