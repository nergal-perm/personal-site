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
  void normalizesCompletePageSpecificShapes() {
    assertEquals(Map.of("heroTitle", "Заголовок.", "lead", "Лид.", "heroImageAlt", "Alt.", "currentTitle", "Сейчас", "current", List.of(
        Map.of("key", "studying", "label", "Изучаю", "layout", "text", "title", "Тема", "text", "Описание"),
        Map.of("key", "building", "label", "Создаю", "layout", "text", "title", "Проект", "text", "Описание"),
        Map.of("key", "reading", "label", "Читаю", "layout", "book", "title", "Книга", "text", "Описание"),
        Map.of("key", "listening", "label", "Слушаю", "layout", "album", "title", "Альбом", "text", "Описание"))), pageSpecific(parser.normalize("editorial/home.md", "home", frontmatter("home"), homeBody(), common())));
    assertEquals(Map.of("listPrincipleTitle", "Принцип списка", "listPrincipleText", "Принцип.", "searchPlaceholder", "Искать", "showcase", List.of(), "pinned", List.of()), pageSpecific(parser.normalize("editorial/essays.md", "essays", frontmatter("essays"), collectionBody("Принцип списка", "Принцип.\n\nПодсказка поиска:: Искать"), common())));
    assertEquals(Map.of("showcase", List.of(Map.of("target", "Claim", "text", "Текст.")), "pinned", List.of("Claim")), pageSpecific(parser.normalize("editorial/claims.md", "claims", frontmatter("claims"), base() + "## Витрина\n\n### [[Claim]]\n\nТекст.", common())));
    assertEquals(Map.of("heading", "Три типа заметок", "groups", List.of("Рабочие"), "showcase", List.of(), "pinned", List.of(), "libraryAction", "Библиотека", "conceptsAction", "Концепты"), pageSpecific(parser.normalize("editorial/notes.md", "notes", frontmatter("notes"), base() + "## Три типа заметок\n\n- Рабочие\n\nДействие библиотеки:: Библиотека\nДействие концептов:: Концепты", common())));
    assertEquals(Map.of("intro", "Введение.", "showcase", List.of(), "pinned", List.of()), pageSpecific(parser.normalize("editorial/music.md", "music", frontmatter("music"), base() + "## Введение\n\nВведение.", common())));
    assertEquals(Map.of("showcase", List.of(), "pinned", List.of()), pageSpecific(parser.normalize("editorial/library.md", "library", frontmatter("library"), base(), common())));
    assertEquals(Map.of("primaryLabel", "Материал", "showcase", List.of(), "pinned", List.of()), pageSpecific(parser.normalize("editorial/concepts.md", "concepts", frontmatter("concepts"), base() + "## Базовый концепт\n\nМетка:: Материал", common())));
    assertEquals(Map.of("date", "2026-07-15", "status", "current", "updatedLabel", "Сегодня.", "sections", List.of(Map.of("label", "Читаю", "title", "Текущий материал", "text", "Текст.")), "questionAction", "Вопрос", "listeningAction", "Запись"), pageSpecific(parser.normalize("editorial/now.md", "now", nowFrontmatter(), nowBody(), common())));
    assertEquals(Map.of("lead", "Лид.", "principles", List.of(List.of("Первый", "Принцип.")), "colophon", "Колофон."), pageSpecific(parser.normalize("editorial/about.md", "about", frontmatter("about"), base() + "## Лид\n\nЛид.\n\n## Принципы\n\n### Первый\n\nПринцип.\n\n## Колофон\n\nКолофон.", common())));
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
  void reportsExactTargetForDuplicateRequiredHeadings() {
    EditorialParser.ManifestValidationException error = assertThrows(EditorialParser.ManifestValidationException.class,
        () -> parser.normalize("editorial/home.md", "home", frontmatter("home"), homeBody() + "\n\n## Hero\n\n### Заголовок\n\nПовтор.", common()));
    assertEquals("heroTitle", error.fieldName());
    assertEquals("requires exactly one heading `## Hero`", error.reason());
  }

  @Test
  void rejectsUnsupportedAndUnknownEditorialPageValues() {
    EditorialParser.ManifestValidationException map = assertThrows(EditorialParser.ManifestValidationException.class,
        () -> parser.normalize("editorial/map.md", "map", frontmatter("map"), base(), common()));
    assertEquals("editorialPage", map.fieldName());
    assertEquals("must be one of: about, claims, concepts, essays, home, library, music, notes, now", map.reason());

    EditorialParser.ManifestValidationException unknown = assertThrows(EditorialParser.ManifestValidationException.class,
        () -> parser.normalize("editorial/home.md", "home", frontmatter("unknown"), base(), common()));
    assertEquals("editorialPage", unknown.fieldName());
    assertEquals("must be one of: about, claims, concepts, essays, home, library, music, notes, now", unknown.reason());
  }

  @Test
  void reportsExactFieldForMalformedAboutPrincipleProse() {
    EditorialParser.ManifestValidationException error = assertThrows(EditorialParser.ManifestValidationException.class,
        () -> parser.normalize("editorial/about.md", "about", frontmatter("about"),
            base() + "## Лид\n\nЛид.\n\n## Принципы\n\n### Первый\n\n## Колофон\n\nКолофон.", common()));
    assertEquals("principles[0][1]", error.fieldName());
    assertEquals("must contain non-empty prose", error.reason());
  }

  @Test
  void reportsMissingAboutLeadBeforeLaterPrincipleValidation() {
    EditorialParser.ManifestValidationException error = assertThrows(EditorialParser.ManifestValidationException.class,
        () -> parser.normalize("editorial/about.md", "about", frontmatter("about"),
            base() + "## Принципы\n\n## Колофон\n\nКолофон.", common()));
    assertEquals("lead", error.fieldName());
    assertEquals("requires heading `## Лид`", error.reason());
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

  @Test
  void preservesPythonAcceptedNowDateFormsForManifestValidation() {
    for (String date : List.of("20260723", "2026-W30-4", "2026W304", "2026-W30", "2026W30")) {
      Map<String, Object> frontmatter = nowFrontmatter();
      frontmatter.put("date", date);
      assertEquals(date, parser.normalize("editorial/now.md", "now", frontmatter, nowBody(), common()).get("date"));
    }
  }

  @Test
  void normalizesNowDateWikilinksToTheirDisplayLabels() {
    Map<String, Object> frontmatter = nowFrontmatter();
    frontmatter.put("date", "[[2026-07-15|2026-07-16]]");

    assertEquals("2026-07-16", parser.normalize("editorial/now.md", "now", frontmatter, nowBody(), common()).get("date"));
  }

  @Test
  void leavesMalformedNowDateWikilinksUnchangedForManifestValidation() {
    Map<String, Object> frontmatter = nowFrontmatter();
    frontmatter.put("date", "[[|2026-07-23]]");

    assertEquals("[[|2026-07-23]]", parser.normalize("editorial/now.md", "now", frontmatter, nowBody(), common()).get("date"));
  }

  @Test
  void rejectsMalformedShowcaseRowsWithPreciseTargetFields() {
    EditorialParser.ManifestValidationException error = assertThrows(EditorialParser.ManifestValidationException.class,
        () -> parser.normalize("editorial/essays.md", "essays", frontmatter("essays"), collectionBody("Принцип списка", "Принцип.\n\nПодсказка поиска:: Искать") + "\n\n## Витрина\n\n### Не ссылка\n\nТекст.", common()));
    assertEquals("showcase[0].target", error.fieldName());
  }

  @Test
  void rejectsTheCompleteOptionalShowcaseMalformedRowMatrix() {
    List<List<String>> cases = List.of(
        List.of("## Витрина\n", "showcase"),
        List.of("## Витрина\n\n### [[Book]]\n", "showcase[0].text"),
        List.of("## Витрина\n\n### Не ссылка\n\nТекст.\n", "showcase[0].target"),
        List.of("## Витрина\n\n### [[Book]]\n\nТекст.\n\n- список\n", "showcase[0].text"),
        List.of("## Витрина\n\n### [[Book]]\n\nТекст.\n\n1. список\n", "showcase[0].text"),
        List.of("## Витрина\n\n### [[Book]]\n\n#### Подзаголовок\n", "showcase[0].text"),
        List.of("## Витрина\n\n### [[Book]]\n\n> цитата\n", "showcase[0].text"));

    for (List<String> item : cases) {
      EditorialParser.ManifestValidationException error = assertThrows(EditorialParser.ManifestValidationException.class,
          () -> parser.normalize("editorial/library.md", "library", frontmatter("library"), base() + item.getFirst(), common()));
      assertEquals(item.get(1), error.fieldName());
    }
  }

  @Test
  void preservesHomeCurrentWikilinkTargetAndDisplayLabelBeforeManifestResolution() {
    Map<String, Object> metadata = parser.normalize("editorial/home.md", "home", frontmatter("home"),
        homeBody().replace("Тема", "[[study-target|Текущий фокус]]"), common());
    Map<String, Object> current = ((List<Map<String, Object>>) metadata.get("current")).getFirst();
    assertEquals("study-target", current.get("target"));
    assertEquals("Текущий фокус", current.get("title"));
  }

  private static Map<String, Object> common() { return new LinkedHashMap<>(Map.of("id", "page", "title", "Страница", "topics", List.of(), "links", List.of())); }
  private static Map<String, Object> frontmatter(String page) { Map<String, Object> map = new LinkedHashMap<>(); map.put("editorialPage", page); return map; }
  private static Map<String, Object> nowFrontmatter() { Map<String, Object> map = frontmatter("now"); map.put("date", "2026-07-15"); map.put("status", "current"); return map; }
  private static String base() { return "## Кратко\n\nКратко.\n\n## Eyebrow\n\nБровь.\n\n"; }
  private static String collectionBody(String heading, String content) { return base() + "## " + heading + "\n\n" + content; }
  private static String homeBody() { return base() + "## Hero\n\n### Заголовок\n\nЗаголовок.\n\n### Лид\n\nЛид.\n\n### Описание изображения\n\nAlt.\n\n## Сейчас\n\n### Изучаю\n\nТема\n\nОписание\n\n### Создаю\n\nПроект\n\nОписание\n\n### Читаю\n\nКнига\n\nОписание\n\n### Слушаю\n\nАльбом\n\nОписание"; }
  private static String nowBody() { return base() + "## Обновлено\n\nСегодня.\n\n## Читаю\n\n### Текущий материал\n\nТекст.\n\nДействие вопроса:: Вопрос\nДействие записи:: Запись"; }
  private static Map<String, Object> pageSpecific(Map<String, Object> metadata) { Map<String, Object> result = new LinkedHashMap<>(metadata); for (String key : List.of("id", "title", "topics", "links", "type", "searchable", "summary", "eyebrow")) result.remove(key); return result; }
}
