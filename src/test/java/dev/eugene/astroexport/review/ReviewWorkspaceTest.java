package dev.eugene.astroexport.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.translation.TranslationPatch;
import dev.eugene.astroexport.translation.TranslationProjection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReviewWorkspaceTest {
  @TempDir
  Path temp;

  @Test
  void writesNormalizedRussianContentAndEditorialReviewFiles() throws Exception {
    Path content = ReviewWorkspace.writeRuReviewFile(temp.resolve("review"), contentEntry());
    String contentText = Files.readString(content);
    assertTrue(contentText.contains("route: /ru/essays/essay/"));
    assertTrue(contentText.contains("targetPath: src/content/blog/ru/essay.md"));
    assertTrue(contentText.endsWith("Русский текст.\n"));
    assertFalse(contentText.contains("  \n"));

    Path editorial = ReviewWorkspace.writeRuReviewFile(temp.resolve("review"), editorialEntry());
    String editorialText = Files.readString(editorial);
    assertFalse(editorialText.contains("\ncurrent:"));
    assertTrue(editorialText.contains("## Сейчас\n\n### Изучаю\n\nНаблюдаемая работа\n\nРусское описание."));
  }

  @Test
  void loadsReviewPatchAndParsesEditorialCurrentCards() throws Exception {
    ManifestEntry entry = editorialEntry();
    Path path = temp.resolve("review/editorial/home/en.md");
    Files.createDirectories(path.getParent());
    Files.writeString(path, """
        ---
        sourceHash: %s
        translationStatus: reviewed
        translatedAt: 2026-07-17
        translationProfile: codex-test-v1
        title: Home
        referenceTranslations:
          paths: {}
        ---
        ## Сейчас

        ### Studying

        Observable work

        English description.
        """.formatted(TranslationProjection.translationSourceHash(entry)));

    TranslationPatch patch = ReviewWorkspace.loadEnglishPatch(temp.resolve("review"), entry);

    assertEquals("reviewed", patch.translationStatus());
    assertEquals("Home", patch.metadata().get("title"));
    assertEquals(List.of(Map.of(
        "label", "Studying",
        "title", "Observable work",
        "text", "English description.")), patch.metadata().get("current"));
    assertEquals(Map.of("paths", Map.of()), patch.referenceTranslations());
  }

  @Test
  void migratesMarkdownAndEditorialJsonOverrides() throws Exception {
    Path overrides = temp.resolve("overrides/en");
    Files.createDirectories(overrides.resolve("blog"));
    Files.writeString(overrides.resolve("blog/essay.md"), "markdown override\n");
    Files.createDirectories(overrides.resolve("editorial"));
    Files.writeString(overrides.resolve("editorial/home.json"), """
        {
          "sourceHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "translationStatus": "generated",
          "translatedAt": "2026-07-17",
          "translationProfile": "codex-test-v1",
          "metadata": {
            "title": "Home",
            "current": [{"label": "Studying", "title": "Work", "text": "Description."}]
          },
          "referenceTranslations": {"paths": {}},
          "body": ""
        }
        """);

    List<Path> written = ReviewWorkspace.migrateOverrides(overrides, temp.resolve("review"));

    assertEquals(List.of(
        temp.resolve("review/blog/essay/en.md"),
        temp.resolve("review/editorial/home/en.md")), written);
    String editorial = Files.readString(temp.resolve("review/editorial/home/en.md"));
    assertTrue(editorial.contains("referenceTranslations:"));
    assertTrue(editorial.contains("## Сейчас"));
    assertFalse(editorial.contains("\ncurrent:"));
  }

  @Test
  void rejectsDuplicateEditorialJsonKeysDuringMigration() throws Exception {
    Path override = temp.resolve("overrides/en/editorial/home.json");
    Files.createDirectories(override.getParent());
    Files.writeString(override, """
        {
          "metadata": {"title": "Home"},
          "metadata": {"title": "Duplicate"}
        }
        """);

    IllegalStateException error = assertThrows(
        IllegalStateException.class,
        () -> ReviewWorkspace.migrateOverrides(
            temp.resolve("overrides/en"), temp.resolve("review")));

    assertTrue(error.getMessage().contains("editorial override"));
  }

  @Test
  void rewritesGeneratedAndReviewedStatuses() {
    String source = """
        ---
        sourceHash: abc
        translationStatus: generated # durable state
        translatedAt: 2026-07-17
        translationProfile: codex-test-v1
        title: English
        ---
        Body with deliberate formatting.
        """;

    String reviewed = ReviewWorkspace.setReviewedStatusPreservingContent(source);
    assertTrue(reviewed.contains("translationStatus: \"reviewed\" # durable state"));
    assertEquals(
        source.replace("translationStatus: generated", "translationStatus: \"reviewed\""),
        reviewed);
    assertTrue(
        ReviewWorkspace.setGeneratedReviewStatus(reviewed)
            .contains("translationStatus: \"generated\" # durable state"));
  }

  @Test
  void rejectsAliasedTranslationStatusDuringReviewedRewrite() {
    String aliased = """
        ---
        statusValue: &status generated
        translationStatus: *status
        ---
        English.
        """;

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> ReviewWorkspace.setReviewedStatusPreservingContent(aliased));

    assertTrue(error.getMessage().contains("explicit translationStatus"));
  }

  @Test
  void rejectsNonStringReviewControlValues() throws Exception {
    ManifestEntry entry = contentEntry();
    Path path = temp.resolve("review/blog/essay/en.md");
    Files.createDirectories(path.getParent());
    Files.writeString(path, """
        ---
        sourceHash:
        - not
        - a string
        translationStatus: generated
        translatedAt: 2026-07-17
        translationProfile: codex-test-v1
        title: English
        description: English description.
        ---
        English body.
        """);

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> ReviewWorkspace.loadEnglishPatch(temp.resolve("review"), entry));

    assertTrue(error.getMessage().contains("sourceHash must be a non-empty string"));
  }

  @Test
  void rejectsSymlinkAndHardlinkReviewTargets() throws Exception {
    Path target = temp.resolve("review/blog/essay/ru.md");
    Files.createDirectories(target.getParent());
    Path outside = temp.resolve("outside.md");
    Files.writeString(outside, "outside\n");
    Files.createSymbolicLink(target, outside);
    assertThrows(
        IllegalArgumentException.class,
        () -> ReviewWorkspace.writeRuReviewFile(temp.resolve("review"), contentEntry()));
    assertEquals("outside\n", Files.readString(outside));

    Files.delete(target);
    Files.createLink(target, outside);
    assertThrows(
        IllegalArgumentException.class,
        () -> ReviewWorkspace.writeRuReviewFile(temp.resolve("review"), contentEntry()));
    assertEquals("outside\n", Files.readString(outside));
  }

  @Test
  void failedAtomicReplacementPreservesPreviousBytes() throws Exception {
    Path target = temp.resolve("review/blog/essay/ru.md");
    Files.createDirectories(target.getParent());
    Files.writeString(target, "previous\n");

    assertThrows(
        java.nio.file.FileAlreadyExistsException.class,
        () -> ReviewWorkspace.replaceAtomicallyForTest(target, "replacement\n", target));

    assertEquals("previous\n", Files.readString(target));
  }

  private static ManifestEntry contentEntry() {
    return new ManifestEntry(
        "blog/Essay.md",
        "src/content/blog/ru/essay.md",
        "/ru/essays/essay/",
        new LinkedHashMap<>(Map.of(
            "id", "essay",
            "title", "Русский заголовок",
            "language", "ru",
            "sourceLanguage", "ru",
            "sourceHash", "a".repeat(64))),
        "Русский текст.  \n");
  }

  private static ManifestEntry editorialEntry() {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "home");
    metadata.put("title", "Главная");
    metadata.put("language", "ru");
    metadata.put("sourceLanguage", "ru");
    metadata.put("sourceHash", "a".repeat(64));
    metadata.put("current", List.of(Map.of(
        "key", "studying",
        "label", "Изучаю",
        "layout", "text",
        "title", "Наблюдаемая работа",
        "text", "Русское описание.")));
    return new ManifestEntry(
        "editorial/home.md",
        "src/data/pages/ru/home.json",
        "/ru/",
        metadata,
        "");
  }
}
