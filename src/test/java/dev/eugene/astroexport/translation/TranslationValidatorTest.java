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
  void rejectsChangedReferenceTargetsAndDuplicateYamlKeys() throws Exception {
    ManifestEntry entry = richEntry();
    writeReview(entry, """
        title: English
        title: Duplicate
        description: Description
        cards:
        - target: essay-two
          text: Text
        """, "English body.");

    TranslationValidator.TranslationValidationException error = assertThrows(
        TranslationValidator.TranslationValidationException.class,
        () -> validator(entry));
    assertTrue(
        error.getMessage().contains("duplicate")
            || error.getMessage().contains("target must remain invariant"));
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
        TranslationProjection.translationSourceHash(entry),
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
          paths:
            /ru/essays/one/:
              title: One
              text: First path.
          routes:
            /ru/essays/dormant/:
              title: Dormant
              text: Dormant path.
        ---
        """.formatted(TranslationProjection.translationSourceHash(entry)));
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
    return new ManifestEntry(
        "editorial/essays.md",
        "src/data/pages/ru/essays.json",
        "/ru/essays/",
        metadata,
        "");
  }
}
