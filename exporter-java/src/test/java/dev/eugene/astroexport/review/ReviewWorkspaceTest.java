package dev.eugene.astroexport.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import dev.eugene.astroexport.translation.TranslationPatch;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
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
  void renderedRussianReviewExactlyMatchesTheFileWriter() throws Exception {
    ManifestEntry entry = contentEntry();
    Path written = ReviewWorkspace.writeRuReviewFile(temp.resolve("review"), entry);

    assertEquals(
        ReviewWorkspace.renderRuReview(entry),
        Files.readString(written));
  }

  @Test
  void russianReviewSerializationCanonicalizesEveryMappingLevel() {
    LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
    nested.put("z", 2);
    nested.put("a", 1);
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("zeta", "last");
    metadata.put("nested", nested);
    metadata.put("alpha", "first");
    metadata.put("id", "canonical");
    ManifestEntry entry = new ManifestEntry(
        "blog/Canonical.md",
        "src/content/blog/ru/canonical.md",
        "/ru/essays/canonical/",
        metadata,
        "Body.");

    String rendered = ReviewWorkspace.renderRuReview(entry);
    FrontmatterDocument parsed = FrontmatterDocument.parse(
        Path.of("ru.md"), "ru.md", rendered);

    assertEquals(
        List.of("alpha", "id", "nested", "route", "targetPath", "zeta"),
        List.copyOf(parsed.metadata().keySet()));
    @SuppressWarnings("unchecked")
    Map<String, Object> parsedNested =
        (Map<String, Object>) parsed.metadata().get("nested");
    assertEquals(List.of("a", "z"), List.copyOf(parsedNested.keySet()));
  }

  @Test
  void generatedStatusSerializationCanonicalizesEveryMappingLevel() {
    String generated = ReviewWorkspace.setGeneratedReviewStatus("""
        ---
        zeta: last
        nested:
          z: 2
          a: 1
        translationStatus: reviewed
        alpha: first
        ---
        English body.
        """);

    FrontmatterDocument parsed = FrontmatterDocument.parse(
        Path.of("en.md"), "en.md", generated);

    assertEquals(
        List.of("alpha", "nested", "translationStatus", "zeta"),
        List.copyOf(parsed.metadata().keySet()));
    @SuppressWarnings("unchecked")
    Map<String, Object> parsedNested =
        (Map<String, Object>) parsed.metadata().get("nested");
    assertEquals(List.of("a", "z"), List.copyOf(parsedNested.keySet()));
    assertEquals("generated", parsed.metadata().get("translationStatus"));
    assertTrue(generated.endsWith("English body.\n"));
  }

  @Test
  void loadsReviewPatchAndParsesEditorialCurrentCards() throws Exception {
    ManifestEntry entry = authoredEditorialEntry();
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
        """.formatted("a".repeat(64)));

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
  void readsCandidateReferencesFromCandidateDirectory() throws Exception {
    byte[] ru = "[label](ref:ref-0001)\n".getBytes(StandardCharsets.UTF_8);
    byte[] en = "[label](ref:ref-0001)\n".getBytes(StandardCharsets.UTF_8);
    Path candidate = temp.resolve("review/blog/essay/candidate");
    Files.createDirectories(candidate);
    Files.write(candidate.resolve("ru.md"), ru);
    Files.write(candidate.resolve("en.md"), en);
    Files.write(candidate.resolve("references.json"), PageReferenceMapCodec.write(new PageReferenceMap(
        PageReferenceMap.SCHEMA_VERSION,
        "vault-ref-page",
        "blog/Essay.md",
        PageReferenceMapCodec.sha256(ru),
        PageReferenceMapCodec.sha256(en),
        List.of("ref-0001"),
        Map.of("ref-0001", new PageReferenceMap.Reference(
            "vault-ref-target", "Target", "", "label")))));

    PageReferenceMap map = ReviewWorkspace.readCandidateReferences(
        temp.resolve("review"), "blog", "essay");

    assertEquals(List.of("ref-0001"), map.order());
    assertEquals("vault-ref-target", map.references().get("ref-0001").targetRef());
  }

  @Test
  void parsesHomeCurrentCardsAgainstAuthoredSnapshot() throws Exception {
    ManifestEntry entry = filteredHomeEntry();
    Path path = temp.resolve("review/editorial/home/en.md");
    Files.createDirectories(path.getParent());
    Files.writeString(path, """
        ---
        sourceHash: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
        translationStatus: reviewed
        translatedAt: 2026-07-17
        translationProfile: codex-test-v1
        title: Home
        ---
        ## Сейчас

        ### Studying

        First work

        First description.

        ### Building

        Second work

        Second description.
        """);

    TranslationPatch patch = ReviewWorkspace.loadEnglishPatch(temp.resolve("review"), entry);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> current =
        (List<Map<String, Object>>) patch.metadata().get("current");
    assertEquals(2, current.size());
    assertEquals("Building", current.get(1).get("label"));
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
            .contains("translationStatus: generated"));
  }

  @Test
  void generatedStatusParsesAndReserializesQuotedYamlKeys() {
    String source = """
        ---
        "translationStatus": reviewed
        title: English
        ---
        Body with deliberate formatting.
        """;

    String generated = ReviewWorkspace.setGeneratedReviewStatus(source);

    assertTrue(generated.contains("translationStatus: generated"));
    assertTrue(generated.endsWith("Body with deliberate formatting.\n"));
  }

  @Test
  void generatedStatusAddsMissingFieldAndRejectsMalformedYaml() {
    String generated = ReviewWorkspace.setGeneratedReviewStatus("""
        ---
        title: English
        ---
        Body.
        """);

    assertTrue(generated.contains("translationStatus: generated"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ReviewWorkspace.setGeneratedReviewStatus("""
            ---
            title: [unterminated
            translationStatus: reviewed
            ---
            Body.
            """));
  }

  @Test
  void reviewedStatusPreservesQuotedKeyAndRejectsMalformedYaml() {
    String quoted = """
        ---
        "translationStatus": generated # durable state
        title: English
        ---
        Body with deliberate formatting.
        """;

    assertEquals(
        quoted.replace(
            "\"translationStatus\": generated",
            "\"translationStatus\": \"reviewed\""),
        ReviewWorkspace.setReviewedStatusPreservingContent(quoted));
    assertThrows(
        IllegalArgumentException.class,
        () -> ReviewWorkspace.setReviewedStatusPreservingContent("""
            ---
            title: [unterminated
            translationStatus: generated
            ---
            Body.
            """));
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
  void rejectsExplicitlyNullReferenceTranslations() throws Exception {
    ManifestEntry entry = contentEntry();
    Path path = temp.resolve("review/blog/essay/en.md");
    Files.createDirectories(path.getParent());
    Files.writeString(path, """
        ---
        sourceHash: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
        translationStatus: generated
        translatedAt: 2026-07-17
        translationProfile: codex-test-v1
        title: English
        description: English description.
        referenceTranslations: null
        ---
        English body.
        """);

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> ReviewWorkspace.loadEnglishPatch(temp.resolve("review"), entry));

    assertTrue(error.getMessage().contains("referenceTranslations must be an object"));
  }

  @Test
  void rejectsMalformedEditorialCurrentMarkdownCases() throws Exception {
    ManifestEntry entry = authoredEditorialEntry();
    List<List<String>> cases = List.of(
        List.of("### Studying\n\nWork\n\nDescription.", "## Сейчас section"),
        List.of("## Сейчас\n\nUnexpected.", "unexpected section content"),
        List.of("## Сейчас\n\n### Studying\n\nWork", "missing a description"),
        List.of("""
            ## Сейчас

            ### Studying

            Work

            Description.

            ### Extra

            Extra work

            Extra description.
            """, "more current cards"));
    Path path = temp.resolve("review/editorial/home/en.md");
    Files.createDirectories(path.getParent());
    for (List<String> item : cases) {
      Files.writeString(path, """
          ---
          sourceHash: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
          translationStatus: reviewed
          translatedAt: 2026-07-17
          translationProfile: codex-test-v1
          title: Home
          ---
          %s
          """.formatted(item.getFirst()));

      IllegalArgumentException error = assertThrows(
          IllegalArgumentException.class,
          () -> ReviewWorkspace.loadEnglishPatch(temp.resolve("review"), entry));

      assertTrue(error.getMessage().contains(item.get(1)));
    }
  }

  @Test
  void ignoresEditorialCurrentWithoutAuthoredTranslationMetadata() throws Exception {
    ManifestEntry entry = editorialEntry();
    Path path = temp.resolve("review/editorial/home/en.md");
    Files.createDirectories(path.getParent());
    Files.writeString(path, """
        ---
        sourceHash: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
        translationStatus: reviewed
        translatedAt: 2026-07-17
        translationProfile: codex-test-v1
        title: Home
        ---
        ## Сейчас

        ### Studying

        Translated work

        Translated description.
        """);

    TranslationPatch patch = ReviewWorkspace.loadEnglishPatch(temp.resolve("review"), entry);

    assertFalse(patch.metadata().containsKey("current"));
    assertEquals("", patch.body());
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
  void publicEnglishReplacementRejectsConcurrentChangesAndPreservesBytes() throws Exception {
    Path target = temp.resolve("review/blog/essay/en.md");
    Files.createDirectories(target.getParent());
    Files.writeString(target, "generated\n");
    byte[] expected = Files.readAllBytes(target);
    Files.writeString(target, "editor changes\n");

    assertThrows(
        ReviewWorkspace.ConcurrentReviewUpdateException.class,
        () -> ReviewWorkspace.replaceEnglishReviewFile(
            temp.resolve("review"),
            "reviewed\n",
            "blog",
            "essay",
            expected));

    assertEquals("editor changes\n", Files.readString(target));
  }

  @Test
  void publicEnglishGuardedReplacementAtomicallyReplacesExpectedFile() throws Exception {
    Path directory = temp.resolve("review/blog/essay");
    Path target = directory.resolve("en.md");
    Files.createDirectories(directory);
    Files.writeString(target, "generated translation\n");

    ReviewWorkspace.replaceEnglishReviewFile(
        temp.resolve("review"),
        "reviewed translation\n",
        "blog",
        "essay",
        Files.readAllBytes(target));

    assertEquals("reviewed translation\n", Files.readString(target));
    try (var paths = Files.list(directory)) {
      assertEquals(List.of("en.md"), paths.map(path -> path.getFileName().toString()).toList());
    }
  }

  @Test
  void publicEnglishReplacementPreservesExistingFileWhenTemporaryCreationFails() throws Exception {
    Assumptions.assumeTrue(
        Files.getFileStore(temp).supportsFileAttributeView("posix"),
        "requires POSIX directory permissions");
    Path directory = temp.resolve("review/blog/essay");
    Path target = directory.resolve("en.md");
    Files.createDirectories(directory);
    Files.writeString(target, "previous valid translation\n");
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(directory);
    try {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));

      assertThrows(
          IllegalStateException.class,
          () -> ReviewWorkspace.replaceEnglishReviewFile(
              temp.resolve("review"),
              "replacement translation\n",
              "blog",
              "essay"));
    } finally {
      Files.setPosixFilePermissions(directory, original);
    }

    assertEquals("previous valid translation\n", Files.readString(target));
    try (var paths = Files.list(directory)) {
      assertEquals(List.of("en.md"), paths.map(path -> path.getFileName().toString()).toList());
    }
  }

  @Test
  void publicEnglishReplacementPreservesExistingFileWhenAtomicExchangeFails() throws Exception {
    Path archive = temp.resolve("review.zip");
    URI uri = URI.create("jar:" + archive.toUri());
    try (var fileSystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
      Path reviewRoot = fileSystem.getPath("/review");
      Path directory = reviewRoot.resolve("blog/essay");
      Path target = directory.resolve("en.md");
      Files.createDirectories(directory);
      Files.writeString(target, "previous valid translation\n");

      assertThrows(
          IllegalStateException.class,
          () -> ReviewWorkspace.replaceEnglishReviewFile(
              reviewRoot,
              "replacement translation\n",
              "blog",
              "essay",
              Files.readAllBytes(target)));

      assertEquals("previous valid translation\n", Files.readString(target));
      try (var paths = Files.list(directory)) {
        assertEquals(List.of("en.md"), paths.map(path -> path.getFileName().toString()).toList());
      }
    }
  }

  @Test
  void doesNotDuplicateExistingEditorialCurrentSection() throws Exception {
    ManifestEntry source = editorialEntry();
    String body = """
        # Тихие системы

        ## Сейчас

        ### Изучаю

        Наблюдаемая работа

        Русское описание.
        """.strip();
    ManifestEntry entry = new ManifestEntry(
        source.sourcePath(),
        source.targetPath(),
        source.route(),
        source.metadata(),
        body);

    Path path = ReviewWorkspace.writeRuReviewFile(temp.resolve("review"), entry);
    String markdown = Files.readString(path);

    assertEquals(1, markdown.split("## Сейчас", -1).length - 1);
    assertTrue(markdown.endsWith(body + "\n"));
    assertFalse(markdown.contains("\ncurrent:"));
  }

  @Test
  void writesAndReadsPublishedSnapshotPair() throws Exception {
    Path review = temp.resolve("review");

    ReviewWorkspace.writePublishedSnapshot(review, "blog", "essay", "ru content\n", "en content\n");

    assertEquals(Optional.of("ru content\n"), ReviewWorkspace.readPublishedRu(review, "blog", "essay"));
    assertEquals(Optional.of("en content\n"), ReviewWorkspace.readPublishedEn(review, "blog", "essay"));
    assertTrue(Files.isRegularFile(review.resolve("blog/essay/published/ru.md")));
    assertTrue(Files.isRegularFile(review.resolve("blog/essay/published/en.md")));

    ReviewWorkspace.writePublishedSnapshot(review, "blog", "essay", "ru v2\n", "en v2\n");
    assertEquals(Optional.of("ru v2\n"), ReviewWorkspace.readPublishedRu(review, "blog", "essay"));
    assertEquals(Optional.of("en v2\n"), ReviewWorkspace.readPublishedEn(review, "blog", "essay"));
  }

  @Test
  void approvedSnapshotUsesManifestEntryInsteadOfMutableOrdinaryRuFile()
      throws Exception {
    Path review = temp.resolve("review");
    ManifestEntry entry = contentEntry();
    Path ordinary = ReviewWorkspace.writeRuReviewFile(review, entry);
    Files.writeString(ordinary, "tampered ordinary ru.md\n");
    byte[] reviewedEnglish = "reviewed English\n".getBytes(StandardCharsets.UTF_8);

    try (ReviewWorkspace.PendingPublishedSnapshot pending =
        ReviewWorkspace.stageApprovedSnapshot(review, entry, reviewedEnglish)) {
      ReviewWorkspace.PublishedSnapshotResult result = pending.commit(List.of());
      assertTrue(result.recoveryPaths().isEmpty());
    }

    assertEquals(
        ReviewWorkspace.renderRuReview(entry),
        Files.readString(review.resolve("blog/essay/published/ru.md")));
    assertEquals(
        "reviewed English\n",
        Files.readString(review.resolve("blog/essay/published/en.md")));
  }

  @Test
  void readPublishedReturnsEmptyWhenNoSnapshotExists() {
    Path review = temp.resolve("review");
    assertEquals(Optional.empty(), ReviewWorkspace.readPublishedRu(review, "blog", "never-published"));
    assertEquals(Optional.empty(), ReviewWorkspace.readPublishedEn(review, "blog", "never-published"));
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

  private static ManifestEntry authoredEditorialEntry() {
    ManifestEntry entry = editorialEntry();
    return new ManifestEntry(
        entry.sourcePath(),
        entry.targetPath(),
        entry.route(),
        entry.metadata(),
        entry.body(),
        "a".repeat(64),
        entry.metadata());
  }

  private static ManifestEntry filteredHomeEntry() {
    LinkedHashMap<String, Object> visible = new LinkedHashMap<>();
    visible.put("id", "home");
    visible.put("title", "Главная");
    visible.put("language", "ru");
    visible.put("sourceLanguage", "ru");
    visible.put("sourceHash", "a".repeat(64));
    visible.put("current", List.of(Map.of(
        "label", "Изучаю",
        "title", "Первая работа",
        "text", "Первое описание.")));
    LinkedHashMap<String, Object> authored = new LinkedHashMap<>(visible);
    authored.put("current", List.of(
        Map.of(
            "label", "Изучаю",
            "title", "Первая работа",
            "text", "Первое описание."),
        Map.of(
            "label", "Создаю",
            "title", "Вторая работа",
            "text", "Второе описание.")));
    return new ManifestEntry(
        "editorial/home.md",
        "src/data/pages/ru/home.json",
        "/ru/",
        visible,
        "",
        "b".repeat(64),
        authored);
  }
}
