package dev.eugene.astroexport.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestResult;
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
        "target", "essay-one",
        "text", "English pick.")), english.metadata().get("cards"));
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
        "Read /ru/notes/one/.", TranslationProjection.translationSourceHash(entry), "reviewed");
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
        ? TranslationProjection.translationSourceHash(entry)
        : entry.translationSourceHash();
  }

  private Path reviewPath(ManifestEntry entry) {
    String collection = entry.targetPath().startsWith("src/data/pages/ru/") ? "editorial" : "blog";
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
        "target", "essay-one",
        "text", "Русский выбор.")));
    return new ManifestEntry(
        "blog/Essay.md",
        "src/content/blog/ru/essay.md",
        "/ru/essays/essay/",
        metadata,
        "Русский текст.");
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
        TranslationProjection.translationSourceHash(new ManifestEntry(
            "editorial/home.md",
            "src/data/pages/ru/home.json",
            "/ru/",
            metadata,
            "")),
        null);
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
}
