package dev.eugene.astroexport.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestResult;
import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.manifest.ManifestBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TranslationValidatorTest {
  @TempDir
  Path temp;

  @Test
  void buildsLocalizedEnglishManifestAndInheritsInvariantFields() throws Exception {
    ManifestEntry russianEntry = richEntry();
    writeReview(
        russianEntry,
        """
        title: English title
        description: English description.
        cards:
        - text: English pick.
        """,
        "English body.");

    ManifestResult result = new TranslationValidator().buildEnglishManifest(
        new ManifestResult(List.of(russianEntry), List.of(), List.of(), List.of()), temp.resolve("review"));

    ManifestEntry english = result.entries().getFirst();
    assertEquals("src/content/blog/en/essay.md", english.targetPath());
    assertEquals("/en/essays/essay/", english.route());
    assertEquals("English body.", english.body());
    assertEquals("English title", english.metadata().get("title"));
    assertEquals("2026-07-15", english.metadata().get("date"));
    assertEquals("en", english.metadata().get("language"));
    assertEquals("ru", english.metadata().get("sourceLanguage"));
    assertEquals("essay", english.metadata().get("translationOf"));
    assertEquals("generated", english.metadata().get("translationStatus"));
    assertEquals(List.of(Map.of(
        "id", "card-one",
        "text", "English pick.")), english.metadata().get("cards"));
  }

  @Test
  void fallsBackToRussianSourceHashWhenTranslationHashIsAbsent() throws Exception {
    ManifestEntry entry = richEntry();
    writeReview(
        entry,
        """
        title: English
        description: Description
        cards:
        - text: Text
        """,
        "English body.",
        "a".repeat(64),
        "generated");

    assertEquals("English", validator(entry).entries().getFirst().metadata().get("title"));
  }

  @Test
  void validatesDormantEditorialCopyFromManifestBuilderAuthoredSnapshot() throws Exception {
    Note concepts = note(
        "editorial/Concepts.md",
        "Концепты",
        "concepts",
        "editorial",
        "curated_page",
        Map.of("editorialPage", "concepts"),
        """
        ## Кратко

        Кратко.

        ## Eyebrow

        Концепты.

        ## Базовый концепт

        Метка:: Базовый
        Материал:: private-concept
        """);
    ManifestEntry entry = new ManifestBuilder()
        .buildRussianManifest(new SelectionResult(List.of(concepts), List.of(), 1, 1))
        .entries()
        .getFirst();
    writeReview(
        entry,
        """
        title: Concepts
        summary: English summary.
        eyebrow: Concepts
        primaryLabel: Core concept
        """,
        "");

    ManifestEntry english = validator(entry).entries().getFirst();

    assertFalse(english.metadata().containsKey("primary"));
    assertFalse(english.metadata().containsKey("primaryLabel"));
    assertEquals("", english.body());
  }

  @Test
  void rejectsMissingExtraAndInvariantTranslationFields() throws Exception {
    ManifestEntry entry = richEntry();
    writeReview(entry, "title: English title\ncards: []\ninvented: value\n", "English body.");

    TranslationValidator.TranslationValidationException error = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> new TranslationValidator().buildEnglishManifest(
            new ManifestResult(List.of(entry), List.of(), List.of(), List.of()), temp.resolve("review")));

    assertTrue(error.getMessage().contains("missing fields: description"));
    assertTrue(error.getMessage().contains("unexpected fields: invented"));
  }

  @Test
  void rejectsStaleInvalidStatusAndInternalRussianRoutes() throws Exception {
    ManifestEntry entry = richEntry();
    writeReview(entry, "title: English\ndescription: Description\ncards:\n- text: Text\n",
        "Read /ru/notes/one/.", "stale", "draft");

    TranslationValidator.TranslationValidationException stale = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));
    assertTrue(stale.getMessage().contains("translationStatus must be generated or reviewed"));

    writeReview(entry, "title: English\ndescription: Description\ncards:\n- text: Text\n",
        "Read /ru/notes/one/.", "stale", "generated");
    TranslationValidator.TranslationValidationException staleHash = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));
    assertTrue(staleHash.getMessage().contains("stale review"));

    writeReview(entry, "title: English\ndescription: Description\ncards:\n- text: Text\n",
        "Read /ru/notes/one/.", "a".repeat(64), "reviewed");
    TranslationValidator.TranslationValidationException route = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));
    assertTrue(route.getMessage().contains("internal /ru/ route"));
  }

  @Test
  void materializesVisibleReferenceTranslationsAndKeepsDormantOnesOut() throws Exception {
    ManifestEntry entry = editorialEntry();
    writeEditorialReview(entry);

    ManifestEntry english = validator(entry).entries().getFirst();

    assertEquals(List.of(Map.of(
        "route", "/en/essays/one/",
        "title", "One",
        "text", "First path.")), english.metadata().get("paths"));
    assertFalse(english.metadata().containsKey("routes"));
  }

  @Test
  void editorialReviewMarkdownDoesNotBecomeManifestBody() throws Exception {
    ManifestEntry entry = homeEntry();
    writeReview(entry, "title: Home\n", """
        ## Сейчас

        ### Studying

        Observable work

        English description.
        """);

    ManifestEntry english = validator(entry).entries().getFirst();

    assertEquals("", english.body());
    assertEquals(List.of(Map.of(
        "label", "Studying",
        "title", "Observable work",
        "text", "English description.")), english.metadata().get("current"));
  }

  @Test
  void rejectsDuplicateYamlKeys() throws Exception {
    ManifestEntry entry = richEntry();
    writeReview(entry, """
        title: English
        title: Duplicate
        description: Description
        cards:
        - text: Text
        """, "English body.");

    TranslationValidator.TranslationValidationException error = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));

    assertTrue(error.getMessage().contains("duplicate"));
  }

  @Test
  void rejectsChangedReferenceTokenTarget() throws Exception {
    ManifestEntry entry = referenceTokenEntry();
    writeReview(entry, """
        title:
        - kind: reference
          target: another-book
        """, "");

    TranslationValidator.TranslationValidationException error = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));

    assertTrue(error.getMessage().contains("target must remain invariant"));
  }

  @Test
  void validatesShowcaseTargetIndependentlyFromTranslatedProse() throws Exception {
    ManifestEntry entry = showcaseEntry();
    writeReview(entry, """
        title: Essays
        showcase:
        - target: essay-one
          text: English pick.
        """, "");

    ManifestEntry english = validator(entry).entries().getFirst();

    assertEquals(List.of(Map.of(
        "target", "essay-one",
        "text", "English pick.")), english.metadata().get("showcase"));

    writeReview(entry, """
        title: Essays
        showcase:
        - target: essay-two
          text: English pick.
        """, "");
    TranslationValidator.TranslationValidationException error = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));
    assertTrue(error.getMessage().contains("showcase[0].target must remain invariant"));
  }

  @Test
  void preservesWhitespaceTextTokensBetweenReferences() throws Exception {
    ManifestEntry entry = whitespaceTokenEntry();
    writeReview(entry, """
        title:
        - kind: reference
          target: first-book
        - kind: text
          value: " "
        - kind: reference
          target: second-book
        """, "");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> title =
        (List<Map<String, Object>>) validator(entry).entries().getFirst().metadata().get("title");

    assertEquals(Map.of("kind", "text", "value", " "), title.get(1));
  }

  @Test
  void rejectsProtectedOrNonvisibleConceptDefinitionHeadings() throws Exception {
    for (String body : List.of(
        "```markdown\n## Definition\n\nHidden.\n```\n",
        "`Code\n## Definition\n\nHidden.\n`\n",
        "<pre>\n## Definition\n\nHidden.\n</pre>\n",
        "## Definition\n\n<!-- Hidden. -->\n",
        "## Definition\n\n%% Hidden. %%\n")) {
      ManifestEntry entry = conceptEntry();
      writeReview(entry, "title: Concept\ndescription: Description.\n", body);

      TranslationValidator.TranslationValidationException error = assertThrows(
          TranslationValidator.TranslationValidationException.class,
          () -> validator(entry));

      assertTrue(
          error.getMessage().contains(
              "concept body must contain a non-empty Definition section"),
          error.getMessage());
    }
  }

  @Test
  void rejectsMissingRussianLanguageEnvelopeFields() throws Exception {
    for (String field : List.of("language", "sourceLanguage")) {
      LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(richEntry().metadata());
      metadata.remove(field);
      ManifestEntry entry = new ManifestEntry(
          "blog/Essay.md",
          "src/content/blog/ru/essay.md",
          "/ru/essays/essay/",
          metadata,
          "Русский текст.");
      writeReview(
          entry,
          "title: English\ndescription: Description\ncards:\n- text: Text\n",
          "English body.",
          "a".repeat(64),
          "generated");

      TranslationValidator.TranslationValidationException error = assertThrows(
          TranslationValidator.TranslationValidationException.class,
          () -> validator(entry));

      assertTrue(error.getMessage().contains("RU " + field + " must be ru"));
    }
  }

  @Test
  void rejectsMalformedTranslatedListAndObjectStructures() throws Exception {
    ManifestEntry entry = richEntry();
    writeReview(
        entry,
        "title: English\ndescription: Description\ncards: []\n",
        "English body.");
    assertTrue(assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry)).getMessage().contains("same length"));

    writeReview(
        entry,
        "title: English\ndescription: Description\ncards:\n- wrong\n",
        "English body.");
    TranslationValidator.TranslationValidationException objectError = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));
    assertTrue(objectError.getMessage().contains("must be an object"), objectError.getMessage());
  }

  @Test
  void wrapsInvalidUtf8ReviewReadsAsTranslationValidationErrors() throws Exception {
    ManifestEntry entry = homeEntry();
    Path path = reviewPath(entry);
    Files.createDirectories(path.getParent());
    Files.write(path, new byte[] {(byte) 0xff});

    TranslationValidator.TranslationValidationException error = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));

    assertTrue(error.reason().contains(path.toString()));
  }

  @Test
  void preservesExternalUrlsWhileLocalizingInheritedInternalRoutes() throws Exception {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(richEntry().metadata());
    metadata.put("cover", "https://example.com/ru/help");
    metadata.put("route", "/ru/notes/internal/");
    ManifestEntry entry = new ManifestEntry(
        "blog/Essay.md",
        "src/content/blog/ru/essay.md",
        "/ru/essays/essay/",
        metadata,
        "Русский текст.");
    writeReview(
        entry,
        "title: English\ndescription: Description\ncards:\n- text: Text\n",
        "English body.");

    ManifestEntry english = validator(entry).entries().getFirst();

    assertEquals("https://example.com/ru/help", english.metadata().get("cover"));
    assertEquals("/en/notes/internal/", english.metadata().get("route"));
  }

  @Test
  void preservesExternalUrlsWithUnicodeHostnames() throws Exception {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(richEntry().metadata());
    metadata.put("cover", "https://пример.рф/ru/help");
    ManifestEntry entry = new ManifestEntry(
        "blog/Essay.md",
        "src/content/blog/ru/essay.md",
        "/ru/essays/essay/",
        metadata,
        "Русский текст.");
    writeReview(
        entry,
        "title: English\ndescription: Description\ncards:\n- text: Text\n",
        "English body.");

    ManifestEntry english = validator(entry).entries().getFirst();

    assertEquals("https://пример.рф/ru/help", english.metadata().get("cover"));
  }

  @Test
  void removesObsidianCommentsFromTranslatedBodies() throws Exception {
    ManifestEntry entry = richEntry();
    writeReview(
        entry,
        "title: English\ndescription: Description\ncards:\n- text: Text\n",
        """
        Visible.
        %%
        Private translation note.
        %%
        Still visible.
        """);

    ManifestEntry english = validator(entry).entries().getFirst();

    assertEquals("Visible.\n\nStill visible.", english.body());
  }

  @Test
  void typedServiceRecordsDoNotRequireTranslatedBodies() throws Exception {
    List<ManifestEntry> entries = List.of(
        typedServiceEntry(
            "album",
            "src/content/music/ru/album.md",
            "/ru/music/album/",
            Map.of("reviewType", "album", "artist", "Artist", "work", "Album")),
        typedServiceEntry(
            "home",
            "src/data/pages/ru/home.json",
            "/ru/",
            Map.of("type", "home", "searchable", false)));

    for (ManifestEntry entry : entries) {
      writeReview(entry, "title: English title\n", "");

      ManifestEntry english = validator(entry).entries().getFirst();

      assertEquals("", english.body());
      assertEquals("English title", english.metadata().get("title"));
    }
  }

  @Test
  void bibliographyWithPublicRussianSynopsisRequiresTranslatedBody() throws Exception {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "book");
    metadata.put("title", "Русская книга");
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("sourceHash", "a".repeat(64));
    metadata.put("authors", List.of("Author"));
    ManifestEntry entry = new ManifestEntry(
        "sources/book.md",
        "src/content/bibliography/ru/book.md",
        "/ru/library/book/",
        metadata,
        "### Введение\n\nРусский конспект.");
    writeReview(entry, "title: English book\n", "");

    TranslationValidator.TranslationValidationException error = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));

    assertTrue(error.getMessage().contains("bibliography body must be non-empty"));
  }

  @Test
  void rejectsUnexpectedReferenceTranslationCatalogField() throws Exception {
    ManifestEntry entry = editorialEntry();
    writeEditorialReview(entry, """
        paths:
          /ru/essays/one/:
            title: One
            text: First path.
        routes:
          /ru/essays/dormant/:
            title: Dormant
            text: Dormant path.
        invented: {}
        """);

    TranslationValidator.TranslationValidationException error = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));

    assertTrue(error.getMessage().contains(
        "referenceTranslations has unexpected fields: invented"));
  }

  @Test
  void rejectsUnexpectedReferenceTranslationRoute() throws Exception {
    ManifestEntry entry = editorialEntry();
    writeEditorialReview(entry, """
        paths:
          /ru/essays/one/:
            title: One
            text: First path.
          /ru/essays/unexpected/:
            title: Unexpected
            text: Unexpected path.
        routes:
          /ru/essays/dormant/:
            title: Dormant
            text: Dormant path.
        """);

    TranslationValidator.TranslationValidationException error = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));

    assertTrue(error.getMessage().contains(
        "referenceTranslations.paths has unexpected references: /ru/essays/unexpected/"));
  }

  @Test
  void rejectsReferenceTranslationsWithoutAuthoredEditorialSource() throws Exception {
    ManifestEntry entry = richEntry();
    writeReview(entry, """
        title: English
        description: Description
        cards:
        - text: Text
        referenceTranslations:
          paths: {}
        """, "English body.");

    TranslationValidator.TranslationValidationException error = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));

    assertTrue(error.getMessage().contains(
        "referenceTranslations require authored editorial source metadata"));
  }

  private ManifestResult validator(ManifestEntry entry) {
    return new TranslationValidator().buildEnglishManifest(
        new ManifestResult(List.of(entry), List.of(), List.of(), List.of()), temp.resolve("review"));
  }

  private void writeReview(ManifestEntry entry, String metadata, String body) throws Exception {
    writeReview(
        entry,
        metadata,
        body,
        requiredHash(entry),
        "generated");
  }

  private void writeReview(
      ManifestEntry entry,
      String metadata,
      String body,
      String sourceHash,
      String status) throws Exception {
    Path path = reviewPath(entry);
    Files.createDirectories(path.getParent());
    Files.writeString(path, """
        ---
        sourceHash: %s
        translationStatus: %s
        translatedAt: 2026-07-17
        translationProfile: codex-test-v1
        %s---
        %s
        """.formatted(sourceHash, status, metadata, body));
  }

  private void writeEditorialReview(ManifestEntry entry) throws Exception {
    writeEditorialReview(entry, """
        paths:
          /ru/essays/one/:
            title: One
            text: First path.
        routes:
          /ru/essays/dormant/:
            title: Dormant
            text: Dormant path.
        """);
  }

  private void writeEditorialReview(ManifestEntry entry, String referenceTranslations)
      throws Exception {
    Path path = reviewPath(entry);
    Files.createDirectories(path.getParent());
    Files.writeString(path, """
        ---
        sourceHash: %s
        translationStatus: generated
        translatedAt: 2026-07-17
        translationProfile: codex-test-v1
        title: Essays
        referenceTranslations:
        %s
        ---
        """.formatted(requiredHash(entry), referenceTranslations.indent(2)));
  }

  private static String requiredHash(ManifestEntry entry) {
    return entry.translationSourceHash() == null
        ? String.valueOf(entry.metadata().get("sourceHash"))
        : entry.translationSourceHash();
  }

  private Path reviewPath(ManifestEntry entry) {
    String collection = entry.targetPath().startsWith("src/data/pages/ru/")
        ? "editorial"
        : entry.targetPath().split("/")[2];
    return temp.resolve("review").resolve(collection).resolve((String) entry.metadata().get("id")).resolve("en.md");
  }

  private static ManifestEntry richEntry() {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "essay");
    metadata.put("title", "Русский заголовок");
    metadata.put("description", "Русское описание.");
    metadata.put("date", "2026-07-15");
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("sourceHash", "a".repeat(64));
    metadata.put("cards", List.of(Map.of(
        "id", "card-one",
        "text", "Русский выбор.")));
    return new ManifestEntry(
        "blog/Essay.md",
        "src/content/blog/ru/essay.md",
        "/ru/essays/essay/",
        metadata,
        "Русский текст.");
  }

  private static ManifestEntry typedServiceEntry(
      String publicId,
      String targetPath,
      String route,
      Map<String, Object> invariants) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", publicId);
    metadata.put("title", "Русский заголовок");
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("translationStatus", "source");
    metadata.put("sourceHash", "a".repeat(64));
    metadata.putAll(invariants);
    return new ManifestEntry(
        "sources/" + publicId + ".md",
        targetPath,
        route,
        metadata,
        "Служебное русское тело.");
  }

  private static ManifestEntry editorialEntry() {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "essays");
    metadata.put("title", "Эссе");
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("sourceHash", "a".repeat(64));
    metadata.put("paths", List.of(Map.of(
        "route", "/ru/essays/one/",
        "title", "Один",
        "text", "Первый путь.")));
    LinkedHashMap<String, Object> authored = new LinkedHashMap<>(metadata);
    authored.put("routes", List.of(Map.of(
        "route", "/ru/essays/dormant/",
        "title", "Скрытый",
        "text", "Скрытый путь.")));
    return new ManifestEntry(
        "editorial/essays.md",
        "src/data/pages/ru/essays.json",
        "/ru/essays/",
        metadata,
        "",
        "b".repeat(64),
        authored);
  }

  private static ManifestEntry homeEntry() {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "home");
    metadata.put("title", "Главная");
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("sourceHash", "a".repeat(64));
    metadata.put("current", List.of(Map.of(
        "label", "Изучаю",
        "title", "Наблюдаемая работа",
        "text", "Русское описание.")));
    return new ManifestEntry(
        "editorial/home.md",
        "src/data/pages/ru/home.json",
        "/ru/",
        metadata,
        "",
        "b".repeat(64),
        metadata);
  }

  private static ManifestEntry referenceTokenEntry() {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "now");
    metadata.put("title", List.of(Map.of("kind", "reference", "target", "book")));
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("sourceHash", "a".repeat(64));
    return new ManifestEntry(
        "editorial/now.md",
        "src/data/pages/ru/now.json",
        "/ru/now/",
        metadata,
        "");
  }

  private static ManifestEntry showcaseEntry() {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "essays");
    metadata.put("title", "Эссе");
    metadata.put("showcase", List.of(Map.of(
        "target", "essay-one",
        "text", "Русский выбор.")));
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("sourceHash", "a".repeat(64));
    return new ManifestEntry(
        "editorial/essays.md",
        "src/data/pages/ru/essays.json",
        "/ru/essays/",
        metadata,
        "");
  }

  private static ManifestEntry whitespaceTokenEntry() {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "now");
    metadata.put("title", List.of(
        Map.of("kind", "reference", "target", "first-book"),
        Map.of("kind", "text", "value", " "),
        Map.of("kind", "reference", "target", "second-book")));
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("sourceHash", "a".repeat(64));
    return new ManifestEntry(
        "editorial/now.md",
        "src/data/pages/ru/now.json",
        "/ru/now/",
        metadata,
        "");
  }

  private static ManifestEntry conceptEntry() {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "concept");
    metadata.put("title", "Концепт");
    metadata.put("description", "Описание.");
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("sourceHash", "a".repeat(64));
    return new ManifestEntry(
        "concepts/Concept.md",
        "src/content/concepts/ru/concept.md",
        "/ru/concepts/concept/",
        metadata,
        "## Определение\n\nРусское определение.");
  }

  private static Note note(
      String path,
      String title,
      String id,
      String collection,
      String type,
      Map<String, Object> extra,
      String body) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("title", title);
    metadata.put("publish", true);
    metadata.put("publicId", id);
    metadata.put("publicCollection", collection);
    metadata.put("publicContentType", type);
    metadata.putAll(extra);
    return new Note(
        Path.of(path),
        path,
        title,
        metadata,
        body,
        true,
        id,
        collection,
        type,
        List.of());
  }
}
