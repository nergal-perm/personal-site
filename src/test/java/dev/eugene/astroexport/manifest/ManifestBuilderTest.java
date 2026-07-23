package dev.eugene.astroexport.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.model.ManifestLink;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ManifestBuilderTest {
  private final ManifestBuilder builder = new ManifestBuilder();

  @Test
  void extractsConceptDefinitionAndStripsMatchingLeadingH1() {
    var entry = only(builder.buildRussianManifest(selection(note("concepts/Organisation.md", "Organisation", "organisation", "concepts", "concept", Map.of("description", "Описание."), "# Organisation #\n\n## Определение\n\nОпределение.\n"))));
    assertEquals("## Определение\n\nОпределение.\n", entry.body());
    assertEquals("src/content/concepts/ru/organisation.md", entry.targetPath());
    assertEquals("/ru/concepts/organisation/", entry.route());
  }

  @Test
  void rejectsMissingProtectedNonvisibleAndMalformedConceptDefinitions() {
    List<String> invalidBodies = List.of(
        "Нет определения.\n",
        "```markdown\n## Определение\n\nСкрыто.\n```\n",
        "`Код\n## Определение\n\nСкрыто.\n`\n",
        "<pre>\n## Определение\n\nСкрыто.\n</pre>\n",
        "## Определение\n\n<!-- Скрыто. -->\n",
        "## Определение\n\n%% Скрыто. %%\n",
        "##\nОпределение\n\nСкрыто.\n",
        "<!--\n## Определение\n\nСкрыто.\n-->\n",
        "%%\n## Определение\n\nСкрыто.\n%%\n");
    for (String body : invalidBodies) {
      ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
          () -> builder.buildRussianManifest(selection(note("concepts/Organisation.md", "Organisation", "organisation", "concepts", "concept", Map.of("description", "Описание."), body))));
      assertEquals("Определение", error.fieldName());
    }
  }

  @Test
  void rejectsUnsupportedPublicationPairsWithPublicationValidatorDiagnostic() {
    ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(note("blog/Legacy.md", "Legacy", "legacy", "blog", "case", Map.of(), ""))));
    assertEquals("publicContentType", error.fieldName());
    assertEquals("must be one of: claim, essay, note", error.reason());
  }

  @Test
  void removesWorkflowFieldsFromNormalizationAndHashing() {
    Note before = note("blog/Essay.md", "Essay", "essay", "blog", "essay", Map.of("description", "Описание."), "Текст.");
    Note after = note("blog/Essay.md", "Essay", "essay", "blog", "essay", Map.of("description", "Описание.", "publicWorkflowStatus", "ready_for_review"), "Текст.");
    assertEquals(only(builder.buildRussianManifest(selection(before))).metadata(), only(builder.buildRussianManifest(selection(after))).metadata());
  }

  @Test
  void normalizesCollectionSpecificMetadataAndTargets() {
    Note music = note("reviews/Album.md", "Album", "album", "music", "album", Map.of("artist", "Artist", "albumTitle", "Album", "genreTags", List.of("jazz")), "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь.");
    Note book = note("bibliography/Book.md", "Book", "book", "bibliography", "book", Map.of("author", "[[Автор]]", "publisher", "Press", "published", 2020), "<div class=\"book-description\"><p>Описание.</p></div>\n\n## Конспект\n\nПункт.");
    Map<String, Object> musicMetadata = byId(builder.buildRussianManifest(selection(music, book)), "album").metadata();
    Map<String, Object> bookMetadata = byId(builder.buildRussianManifest(selection(music, book)), "book").metadata();
    assertEquals("Album", musicMetadata.get("work"));
    assertEquals("Artist", musicMetadata.get("artist"));
    assertEquals(List.of("Автор"), bookMetadata.get("authors"));
    assertEquals("Press · 2020", bookMetadata.get("publication"));
  }

  @Test
  void rewritesPublicBodyLinksRecordsProvenanceAndCollectsAssets() {
    Note source = note("blog/Source.md", "Source", "source", "blog", "note", Map.of(), "[[Public|ссылка]] ![[cover.png]] [[Private|скрыто]]");
    Note target = note("blog/Public.md", "Public", "public", "blog", "essay", Map.of(), "");
    var result = builder.buildRussianManifest(selection(source, target));
    assertEquals("[ссылка](/ru/essays/public/) ![[cover.png]] скрыто", byId(result, "source").body());
    assertEquals(List.of("cover.png"), result.assets());
    assertEquals("Public", result.retainedLinks().getFirst().target());
    assertEquals("Private", result.strippedLinks().getFirst().target());
  }

  @Test
  void blocksUnpublishedTransclusionsAndAmbiguousTitles() {
    Note embedded = note("blog/Source.md", "Source", "source", "blog", "note", Map.of(), "![[Private]]");
    assertThrows(ManifestBuilder.ManifestTransclusionException.class, () -> builder.buildRussianManifest(selection(embedded)));
    Note source = note("blog/Source.md", "Source", "source", "blog", "note", Map.of(), "[[Shared]]");
    Note first = note("blog/First.md", "Shared", "first", "blog", "note", Map.of(), "");
    Note second = note("blog/Second.md", "Shared", "second", "blog", "note", Map.of(), "");
    assertThrows(ManifestBuilder.ManifestValidationException.class, () -> builder.buildRussianManifest(selection(source, first, second)));
  }

  @Test
  void validatesDatesAndTypedCommonFieldsAndHashesDeterministically() {
    Note invalidDate = note("blog/Essay.md", "Essay", "essay", "blog", "essay", Map.of("date", "2024-13-99"), "");
    assertEquals("date", assertThrows(ManifestBuilder.ManifestValidationException.class, () -> builder.buildRussianManifest(selection(invalidDate))).fieldName());
    Note invalidReadTime = note("blog/Essay.md", "Essay", "essay", "blog", "essay", Map.of("readTime", 0), "");
    assertEquals("readTime", assertThrows(ManifestBuilder.ManifestValidationException.class, () -> builder.buildRussianManifest(selection(invalidReadTime))).fieldName());
    Note first = note("blog/Essay.md", "Essay", "essay", "blog", "essay", Map.of(), "Первый.");
    Note second = note("blog/Essay.md", "Essay", "essay", "blog", "essay", Map.of(), "Второй.");
    assertNotEquals(only(builder.buildRussianManifest(selection(first))).metadata().get("sourceHash"), only(builder.buildRussianManifest(selection(second))).metadata().get("sourceHash"));
  }

  @Test
  void emitsEntriesInSourcePathOrder() {
    var result = builder.buildRussianManifest(selection(note("z/Second.md", "Second", "second", "blog", "note", Map.of(), ""), note("a/First.md", "First", "first", "blog", "note", Map.of(), "")));
    assertEquals(List.of("a/First.md", "z/Second.md"), result.entries().stream().map(entry -> entry.sourcePath()).toList());
  }

  @Test
  void tokenizesNestedEditorialHeadingsButKeepsPageTitleStructural() {
    Note now = note("editorial/Now.md", "[[Книга]]", "now", "editorial", "curated_page",
        Map.of("editorialPage", "now", "date", "2026-07-15", "status", "current"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nСейчас.\n\n## Обновлено\n\nСегодня.\n\n## Читаю\n\n### [[Книга]]\n\nТекст.\n\nДействие вопроса:: Вопрос\nДействие записи:: Запись");
    Note book = note("bibliography/Book.md", "Книга", "book", "bibliography", "book", Map.of("author", "Автор"), "");
    var result = builder.buildRussianManifest(selection(now, book));
    Map<String, Object> metadata = byId(result, "now").metadata();
    assertEquals("[[Книга]]", metadata.get("title"));
    assertEquals(List.of(Map.of("kind", "reference", "target", "book")), ((List<Map<String, Object>>) metadata.get("sections")).getFirst().get("title"));
    assertEquals("Книга", result.retainedLinks().getFirst().target());
  }

  @Test
  void resolvesHomeCurrentTargetsAndPreservesWikilinkDisplayLabels() {
    Note home = note("editorial/Home.md", "Главная", "home", "editorial", "curated_page", Map.of("editorialPage", "home"), homeWithLinkedCurrentCards());
    Note study = note("blog/Study.md", "Study source", "study-target", "blog", "essay", Map.of(), "");
    Note build = note("concepts/Build.md", "Build source", "build-target", "concepts", "concept", Map.of("description", "Описание."), "## Определение\n\nОпределение.");
    Note book = note("bibliography/Book.md", "Book source", "book-target", "bibliography", "book", Map.of("author", "Автор"), "");
    Note album = note("reviews/Album.md", "Album source", "album-target", "music", "album", Map.of("artist", "Автор", "albumTitle", "Альбом"), "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь.");
    Map<String, Object> metadata = byId(builder.buildRussianManifest(selection(home, study, build, book, album)), "home").metadata();
    List<Map<String, Object>> current = (List<Map<String, Object>>) metadata.get("current");
    assertEquals(List.of("study-target", "build-target", "book-target", "album-target"), current.stream().map(item -> item.get("target")).toList());
    assertEquals("Текущий фокус", current.getFirst().get("title"));
    assertEquals("book", current.get(2).get("layout"));
    assertEquals("album", current.get(3).get("layout"));
  }

  @Test
  void allowsHomeCurrentPlainTitlesWithoutTargets() {
    Note home = note("editorial/Home.md", "Главная", "home", "editorial", "curated_page", Map.of("editorialPage", "home"),
        homeWithLinkedCurrentCards().replace("[[Build source|Текущая сборка]]", "Текущая сборка"));
    Note study = note("blog/Study.md", "Study source", "study-target", "blog", "essay", Map.of(), "");
    Note book = note("bibliography/Book.md", "Book source", "book-target", "bibliography", "book", Map.of("author", "Автор"), "");
    Note album = note("reviews/Album.md", "Album source", "album-target", "music", "album", Map.of("artist", "Автор", "albumTitle", "Альбом"), "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь.");
    Map<String, Object> item = ((List<Map<String, Object>>) byId(builder.buildRussianManifest(selection(home, study, book, album)), "home").metadata().get("current")).get(1);
    assertFalse(item.containsKey("target"));
    assertEquals("Текущая сборка", item.get("title"));
  }

  @Test
  void validatesAutomaticCollectionShowcaseTargetsAndFiltersUnpublishedEditorialReferences() {
    Note essays = note("editorial/Essays.md", "Эссе", "essays", "editorial", "curated_page", Map.of("editorialPage", "essays"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nТексты.\n\n## Принцип списка\n\nПринцип.\n\nПодсказка поиска:: Искать\n\n## Витрина\n\n### [[Book source]]\n\nТекст.");
    Note book = note("bibliography/Book.md", "Book source", "book", "bibliography", "book", Map.of("author", "Автор"), "");
    ManifestBuilder.ManifestValidationException showcase = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(essays, book)));
    assertEquals("showcase[0].target", showcase.fieldName());

    Note concepts = note("editorial/Concepts.md", "Концепты", "concepts", "editorial", "curated_page", Map.of("editorialPage", "concepts"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nКонцепты.\n\n## Базовый концепт\n\nМетка:: Базовый\nМатериал:: private-concept");
    var result = builder.buildRussianManifest(selection(concepts));
    Map<String, Object> metadata = only(result).metadata();
    assertFalse(metadata.containsKey("primary"));
    assertFalse(metadata.containsKey("primaryLabel"));
    assertEquals("private-concept", result.strippedLinks().getFirst().target());
  }

  @Test
  void normalizesJavaDateObjectsAndDateStrings() {
    Note dated = note("blog/Dated.md", "Датировано", "dated", "blog", "essay", Map.of("date", LocalDate.of(2024, 5, 1), "updated", "2024-05-02"), "");
    Map<String, Object> metadata = only(builder.buildRussianManifest(selection(dated))).metadata();
    assertEquals("2024-05-01", metadata.get("date"));
    assertEquals("2024-05-02", metadata.get("updated"));
  }

  @Test
  void filtersOnlyDeclaredEditorialReferenceShapesAndRecordsProvenance() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "home");
    metadata.put("featured", "prototype-featured");
    metadata.put("featuredLabel", "Prototype label");
    metadata.put("featuredTitle", "Prototype title");
    metadata.put("featuredText", "Prototype text");
    metadata.put("featuredTraceAlt", "Prototype trace");
    metadata.put("primary", "published-primary");
    metadata.put("selected", List.of("published-first", "prototype-selected", "published-second"));
    metadata.put("items", List.of("prototype-item", "published-first"));
    metadata.put("paths", List.of(Map.of("route", "published-first", "title", "First"), Map.of("route", "prototype-path", "title", "Remove"), Map.of("route", "published-second", "title", "Second")));
    metadata.put("routes", List.of(Map.of("route", "prototype-route", "title", "Remove"), Map.of("route", "published-primary", "title", "Primary")));
    metadata.put("unrelated", Map.of("route", "prototype-unrelated"));
    List<ManifestLink> stripped = new java.util.ArrayList<>();

    ManifestBuilder.filterEditorialReferences("editorial/Home.md", metadata, List.of(referenceNote("published-first"), referenceNote("published-primary"), referenceNote("published-second")), stripped);

    assertEquals(Map.of(
        "id", "home", "primary", "published-primary", "selected", List.of("published-first", "published-second"), "items", List.of("published-first"),
        "paths", List.of(Map.of("route", "published-first", "title", "First"), Map.of("route", "published-second", "title", "Second")),
        "routes", List.of(Map.of("route", "published-primary", "title", "Primary")), "unrelated", Map.of("route", "prototype-unrelated")), metadata);
    assertEquals(List.of(
        new ManifestLink("editorial/Home.md", "prototype-featured", "editorial"),
        new ManifestLink("editorial/Home.md", "prototype-selected", "editorial"),
        new ManifestLink("editorial/Home.md", "prototype-item", "editorial"),
        new ManifestLink("editorial/Home.md", "prototype-path", "editorial"),
        new ManifestLink("editorial/Home.md", "prototype-route", "editorial")), stripped);
  }

  @Test
  void prunesOnlyPageSpecificEditorialScalarDependencies() {
    for (String page : List.of("home", "concepts")) {
      String reference = page.equals("home") ? "featured" : "primary";
      String dependent = page.equals("home") ? "featuredLabel" : "primaryLabel";
      Map<String, Object> metadata = new LinkedHashMap<>(Map.of("id", page, reference, "prototype", dependent, "stale", "unrelated", "survives"));
      List<ManifestLink> stripped = new java.util.ArrayList<>();
      ManifestBuilder.filterEditorialReferences("editorial/" + page + ".md", metadata, List.of(), stripped);
      assertFalse(metadata.containsKey(reference));
      assertFalse(metadata.containsKey(dependent));
      assertEquals("survives", metadata.get("unrelated"));
      assertEquals(List.of(new ManifestLink("editorial/" + page + ".md", "prototype", "editorial")), stripped);
    }
  }

  @Test
  void excludesDeprecatedMusicEditorialFieldsAndHashesAfterReferenceFiltering() {
    Note music = note("editorial/Music.md", "Музыка", "music", "editorial", "curated_page", Map.of("editorialPage", "music", "featured", "private", "formatLabel", "Формат", "focusLabel", "Фокус", "focusText", "Текст"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nМузыка.\n\n## Введение\n\nВведение.");
    Map<String, Object> musicMetadata = only(builder.buildRussianManifest(selection(music))).metadata();
    assertFalse(musicMetadata.containsKey("featured"));
    assertFalse(musicMetadata.containsKey("formatLabel"));
    assertFalse(musicMetadata.containsKey("focusLabel"));
    assertFalse(musicMetadata.containsKey("focusText"));

    Map<String, Object> filtered = new LinkedHashMap<>(Map.of("id", "home", "featured", "private", "featuredLabel", "Private label"));
    List<ManifestLink> stripped = new java.util.ArrayList<>();
    ManifestBuilder.filterEditorialReferences("editorial/Home.md", filtered, List.of(), stripped);
    assertEquals(ManifestBuilder.sourceHash(filtered, "Body."), ManifestBuilder.sourceHash(Map.of("id", "home"), "Body."));
  }

  @Test
  void sanitizesObsidianCommentsBeforeMetadataAndHashing() {
    Note music = note("reviews/Album.md", "Album", "album", "music", "album", Map.of("artist", "Artist", "albumTitle", "Album"),
        "## Контекст записи\n\nВидимый %%PRIVATE_CONTEXT%% текст с `%%INLINE%%`.\n\n```text\n%%FENCED%%\n```\n\n## Личная связь\n\nВидимая %%PRIVATE_ASSOCIATION%% связь.");
    var entry = only(builder.buildRussianManifest(selection(music)));
    assertFalse((entry.metadata().toString() + entry.body()).contains("PRIVATE_"));
    assertEquals(ManifestBuilder.sourceHash(entry.metadata(), entry.body()), entry.metadata().get("sourceHash"));
  }

  @Test
  void usesEditorialSpecificCommonMetadataWithoutContentFieldLeakage() {
    Note library = note("editorial/Library.md", "Библиотека", "library", "editorial", "curated_page", Map.of(
        "editorialPage", "library", "description", "Не экспортировать.", "tags", List.of("private"),
        "aliases", List.of("Приватный псевдоним"), "date", "2024-01-01", "updated", "2024-01-02"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nЧтение.");
    var entry = only(builder.buildRussianManifest(selection(library)));
    Map<String, Object> metadata = entry.metadata();
    assertEquals("library", metadata.get("id"));
    assertEquals("Библиотека", metadata.get("title"));
    assertEquals(List.of(), metadata.get("topics"));
    assertEquals(List.of(), metadata.get("links"));
    assertEquals("ru", metadata.get("language"));
    for (String leaked : List.of("publish", "description", "tags", "aliases", "date", "updated", "cover", "foundational", "readTime")) assertFalse(metadata.containsKey(leaked));
    assertEquals(ManifestBuilder.sourceHash(metadata, entry.body()), metadata.get("sourceHash"));
  }

  @Test
  void keepsEditorialNowDateOnlyThroughPageSpecificParsing() {
    Note now = note("editorial/Now.md", "Сейчас", "now", "editorial", "curated_page", Map.of(
        "editorialPage", "now", "date", "2026-07-15", "updated", "2026-07-16", "status", "current"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nСейчас.\n\n## Обновлено\n\nСегодня.\n\n## Читаю\n\n### Материал\n\nТекст.\n\nДействие вопроса:: Вопрос\nДействие записи:: Запись");
    Map<String, Object> metadata = only(builder.buildRussianManifest(selection(now))).metadata();
    assertEquals("2026-07-15", metadata.get("date"));
    assertFalse(metadata.containsKey("updated"));
  }

  @Test
  void stringifiesBibliographyPublicationAndReadingStatusAndRejectsNonStringUseAndBoundary() {
    Note book = note("bibliography/Book.md", "Книга", "book", "bibliography", "book", Map.of(
        "author", "Автор", "publication", 2024, "readingStatus", 42), "");
    Map<String, Object> metadata = only(builder.buildRussianManifest(selection(book))).metadata();
    assertEquals("2024", metadata.get("publication"));
    assertEquals("42", metadata.get("readingStatus"));

    for (String field : List.of("use", "boundary")) {
      Map<String, Object> extra = new LinkedHashMap<>(Map.of("author", "Автор"));
      extra.put(field, 42);
      ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
          () -> builder.buildRussianManifest(selection(note("bibliography/Book.md", "Книга", "book", "bibliography", "book", extra, ""))));
      assertEquals(field, error.fieldName());
    }
    assertSelectedQuoteError("bad", "selectedQuote");
    assertSelectedQuoteError(Map.of("kind", "invalid", "text", "Текст"), "selectedQuote.kind");
    assertSelectedQuoteError(Map.of("kind", "quote", "text", " "), "selectedQuote.text");
    assertSelectedQuoteError(Map.of("text", "Текст", "locator", 42), "selectedQuote.locator");
  }

  @Test
  void rejectsTypedClaimListsAndMalformedSourceRecords() {
    for (String field : List.of("claimKinds", "supports", "opposes", "assumes", "refines", "contradicts")) {
      Map<String, Object> extra = new LinkedHashMap<>(Map.of("statement", "Тезис."));
      extra.put(field, List.of(42));
      ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
          () -> builder.buildRussianManifest(selection(note("claims/Claim.md", "Тезис", "claim", "blog", "claim", extra, ""))));
      assertEquals(field, error.fieldName());
    }
    ManifestBuilder.ManifestValidationException sources = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(note("claims/Claim.md", "Тезис", "claim", "blog", "claim", Map.of("statement", "Тезис.", "sources", "не список"), ""))));
    assertEquals("sources", sources.fieldName());
    ManifestBuilder.ManifestValidationException sourceObject = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(note("claims/Claim.md", "Тезис", "claim", "blog", "claim", Map.of("statement", "Тезис.", "sources", List.of("не объект")), ""))));
    assertEquals("sources[0]", sourceObject.fieldName());
  }

  @Test
  void retainsFrontmatterLinksOnlyWhenTheyArePublicIds() {
    Note source = note("blog/Source.md", "Источник", "source", "blog", "note", Map.of("links", List.of("Public title", "Public alias", "notes/Target", "public-id")), "");
    Note target = new Note(Path.of("notes/Target.md"), "notes/Target.md", "Public title", Map.of("title", "Public title", "publish", true, "publicId", "public-id", "publicCollection", "blog", "publicContentType", "note"), "", true, "public-id", "blog", "note", List.of("Public alias"));
    var result = builder.buildRussianManifest(selection(source, target));
    assertEquals(List.of("public-id"), byId(result, "source").metadata().get("links"));
    assertEquals(List.of("Public title", "Public alias", "notes/Target"), result.strippedLinks().stream().filter(link -> link.kind().equals("frontmatter")).map(ManifestLink::target).toList());
    assertEquals(List.of("public-id"), result.retainedLinks().stream().filter(link -> link.kind().equals("frontmatter")).map(ManifestLink::target).toList());
  }

  @Test
  void hashesMetadataWithThePythonJsonSerializationShape() {
    Note essay = note("blog/Essay.md", "Эссе", "essay", "blog", "essay", Map.of(), "Текст.");
    assertEquals("be5955d941c6bff7f72cb68ae4d79ec3d7ba6209b3d206d224765644eb743a23",
        only(builder.buildRussianManifest(selection(essay))).metadata().get("sourceHash"));
  }

  @Test
  void resolvesClaimSourceReferencesAndRichTextWithProvenance() {
    Note source = note("claims/Source.md", "Источник", "source", "blog", "claim", Map.of(
        "statement", "Тезис.", "sources", List.of(Map.of("link", "[[Книга]]", "evidence", "См. [[Опубликованный]].", "locator", "Введение"))), "");
    Note claim = note("claims/Published.md", "Опубликованный", "published", "blog", "claim", Map.of("statement", "Другой тезис."), "");
    Note book = note("bibliography/Book.md", "Книга", "book", "bibliography", "book", Map.of("author", "Автор"), "");
    var result = builder.buildRussianManifest(selection(source, claim, book));
    Map<String, Object> sourceMetadata = byId(result, "source").metadata();
    Map<String, Object> sourceRecord = ((List<Map<String, Object>>) sourceMetadata.get("sources")).getFirst();
    assertEquals(Map.of("label", "Книга", "target", "book"), sourceRecord.get("link"));
    assertEquals(List.of(Map.of("kind", "text", "value", "См. "), Map.of("kind", "reference", "target", "published"), Map.of("kind", "text", "value", ".")), sourceRecord.get("evidence"));
    assertEquals(2, result.retainedLinks().stream().filter(link -> link.kind().equals("frontmatter")).count());
  }

  @Test
  void rejectsEmbeddedClaimReferenceLinksWithFieldSpecificDiagnostics() {
    Note relation = note("claims/Relation.md", "Тезис", "relation", "blog", "claim", Map.of(
        "statement", "Тезис.", "supports", List.of("![[Книга]]")), "");
    ManifestBuilder.ManifestValidationException relationError = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(relation)));
    assertEquals("supports[0]", relationError.fieldName());
    assertEquals("must not be an embed", relationError.reason());

    Note source = note("claims/Source.md", "Тезис", "source", "blog", "claim", Map.of(
        "statement", "Тезис.", "sources", List.of(Map.of("link", "![[Книга]]"))), "");
    ManifestBuilder.ManifestValidationException sourceError = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(source)));
    assertEquals("sources[0].link", sourceError.fieldName());
    assertEquals("must not be an embed", sourceError.reason());
  }

  @Test
  void reportsAmbiguousShowcaseTargetsWithTheShowcaseResolutionDiagnostic() {
    Note essays = note("editorial/Essays.md", "Эссе", "essays", "editorial", "curated_page", Map.of("editorialPage", "essays"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nТексты.\n\n## Принцип списка\n\nПринцип.\n\nПодсказка поиска:: Искать\n\n## Витрина\n\n### [[Общее]]\n\nТекст.");
    Note first = note("blog/First.md", "Общее", "first", "blog", "essay", Map.of(), "");
    Note second = note("blog/Second.md", "Общее", "second", "blog", "essay", Map.of(), "");

    ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(essays, first, second)));
    assertEquals("showcase[0].target", error.fieldName());
    assertEquals("must resolve to exactly one published entry", error.reason());
  }

  private static dev.eugene.astroexport.model.ManifestEntry only(dev.eugene.astroexport.model.ManifestResult result) { return result.entries().getFirst(); }
  private static dev.eugene.astroexport.model.ManifestEntry byId(dev.eugene.astroexport.model.ManifestResult result, String id) { return result.entries().stream().filter(entry -> id.equals(entry.metadata().get("id"))).findFirst().orElseThrow(); }
  private static SelectionResult selection(Note... notes) { return new SelectionResult(List.of(notes), List.of(), notes.length, notes.length); }
  private static Note note(String path, String title, String id, String collection, String type, Map<String, Object> extra, String body) {
    Map<String, Object> metadata = new LinkedHashMap<>(); metadata.put("title", title); metadata.put("publish", true); metadata.put("publicId", id); metadata.put("publicCollection", collection); metadata.put("publicContentType", type); metadata.putAll(extra);
    return new Note(Path.of(path), path, title, metadata, body, true, id, collection, type, List.of());
  }
  private static Note referenceNote(String id) { return new Note(Path.of(id + ".md"), id + ".md", id, Map.of(), "", true, id, "blog", "note", List.of()); }
  private void assertSelectedQuoteError(Object selectedQuote, String field) {
    ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(note("bibliography/Book.md", "Книга", "book", "bibliography", "book", Map.of("author", "Автор", "selectedQuote", selectedQuote), ""))));
    assertEquals(field, error.fieldName());
  }
  private static String homeWithLinkedCurrentCards() { return "## Кратко\n\nКратко.\n\n## Eyebrow\n\nГлавная.\n\n## Hero\n\n### Заголовок\n\nЗаголовок.\n\n### Лид\n\nЛид.\n\n### Описание изображения\n\nAlt.\n\n## Сейчас\n\n### Изучаю\n\n[[Study source|Текущий фокус]]\n\nОписание.\n\n### Создаю\n\n[[Build source|Текущая сборка]]\n\nОписание.\n\n### Читаю\n\n[[Book source|Текущая книга]]\n\nОписание.\n\n### Слушаю\n\n[[Album source|Текущий альбом]]\n\nОписание."; }
}
