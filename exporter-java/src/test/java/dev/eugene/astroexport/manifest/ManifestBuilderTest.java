package dev.eugene.astroexport.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.model.ManifestLink;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
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
  void preservesNonmatchingAndIndentedConceptH1Headings() {
    String nonmatching = "# Other title\n\n## Определение\n\nОпределение.\n";
    String indented = "    # Organisation\n\n## Определение\n\nОпределение.\n";

    assertEquals(nonmatching, only(builder.buildRussianManifest(selection(
        note("concepts/Organisation.md", "Organisation", "organisation", "concepts", "concept", Map.of("description", "Описание."), nonmatching)))).body());
    assertEquals(indented, only(builder.buildRussianManifest(selection(
        note("concepts/Organisation.md", "Organisation", "organisation", "concepts", "concept", Map.of("description", "Описание."), indented)))).body());
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
  void ignoresWorkflowFrontmatterForEditorialMetadataBodyAndHash() {
    String body = "## Кратко\n\nЭссе.\n\n## Eyebrow\n\nТексты.\n\n## Принцип списка\n\nПолный список.\n\nПодсказка поиска:: Искать";
    Note before = note("editorial/Essays.md", "Эссе", "essays", "editorial", "curated_page", Map.of("editorialPage", "essays"), body);
    Note after = note("editorial/Essays.md", "Эссе", "essays", "editorial", "curated_page", Map.of(
        "editorialPage", "essays",
        "publicWorkflowStatus", "ready_for_review",
        "publicTranslationStatus", "generated",
        "publicWorkflowUpdated", "2026-07-18T12:00:00+04:00",
        "publicWorkflowDiagnostic", ""), body);

    var beforeEntry = only(builder.buildRussianManifest(selection(before)));
    var afterEntry = only(builder.buildRussianManifest(selection(after)));
    assertEquals(beforeEntry.metadata(), afterEntry.metadata());
    assertEquals(beforeEntry.body(), afterEntry.body());
  }

  @Test
  void populatesAuthoredTranslationStateForRealEditorialEntries() {
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

    var entry = only(builder.buildRussianManifest(selection(concepts)));

    assertFalse(entry.metadata().containsKey("primary"));
    assertFalse(entry.metadata().containsKey("primaryLabel"));
    assertEquals("private-concept", entry.translationSourceMetadata().get("primary"));
    assertEquals("Базовый", entry.translationSourceMetadata().get("primaryLabel"));
    assertTrue(entry.translationSourceHash() != null && !entry.translationSourceHash().isBlank());
  }

  @Test
  void refreshesAuthoredTranslationStateAfterResolvingEditorialPins() {
    Note essays = note(
        "editorial/Essays.md",
        "Эссе",
        "essays",
        "editorial",
        "curated_page",
        Map.of("editorialPage", "essays"),
        """
        ## Кратко

        Кратко.

        ## Eyebrow

        Эссе.

        ## Принцип списка

        Принцип.

        Подсказка поиска:: Искать

        ## Витрина

        ### [[Essay One]]

        Начать здесь.
        """);
    Note target = note(
        "blog/Essay One.md",
        "Essay One",
        "essay-one",
        "blog",
        "essay",
        Map.of(),
        "");

    var entry = byId(builder.buildRussianManifest(selection(essays, target)), "essays");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> authoredShowcase =
        (List<Map<String, Object>>) entry.translationSourceMetadata().get("showcase");

    assertEquals("essay-one", authoredShowcase.getFirst().get("target"));
    assertEquals(entry.metadata().get("sourceHash"), entry.translationSourceHash());
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
  void decodesBookDescriptionHtmlEntitiesBeforeMetadataHashing() {
    String body = "<div class=\"book-description\"><p>&quot;Books &amp; &lt;Notes&gt;&quot;&nbsp;&copy; &reg;&#65;&#x42;</p></div>";
    Note encoded = note("bibliography/Book.md", "Book", "book", "bibliography", "book", Map.of("author", "Автор"), body);
    Note decoded = note("bibliography/Book.md", "Book", "book", "bibliography", "book", Map.of("author", "Автор", "description", "\"Books & <Notes>\" © ®AB"), "");

    var encodedEntry = only(builder.buildRussianManifest(selection(encoded)));
    var decodedEntry = only(builder.buildRussianManifest(selection(decoded)));
    assertEquals("\"Books & <Notes>\" © ®AB", encodedEntry.metadata().get("description"));
    assertEquals(decodedEntry.metadata().get("sourceHash"), encodedEntry.metadata().get("sourceHash"));
  }

  @Test
  void collapsesUnicodeHtmlWhitespaceInBookDescriptionsBeforeHashing() {
    Note encoded = note("bibliography/Book.md", "Book", "book", "bibliography", "book", Map.of("author", "Автор"),
        "<div class=\"book-description\"><p>A&emsp;B&ensp;C&thinsp;D</p></div>");
    Note decoded = note("bibliography/Book.md", "Book", "book", "bibliography", "book",
        Map.of("author", "Автор", "description", "A B C D"), "");

    var encodedEntry = only(builder.buildRussianManifest(selection(encoded)));
    var decodedEntry = only(builder.buildRussianManifest(selection(decoded)));
    assertEquals("A B C D", encodedEntry.metadata().get("description"));
    assertEquals(decodedEntry.metadata().get("sourceHash"), encodedEntry.metadata().get("sourceHash"));
  }

  @Test
  void stringifiesBibliographyScalarsAndNullableAuthorsLikePython() {
    Map<String, Object> direct = nullableMap(
        "authors", Arrays.asList("[[Автор]]", null),
        "publication", Boolean.TRUE,
        "readingStatus", Boolean.FALSE);
    Note directBook = note("bibliography/Direct.md", "Direct", "direct", "bibliography", "book", direct, "");
    Note derivedBook = note("bibliography/Derived.md", "Derived", "derived", "bibliography", "book", Map.of(
        "author", "Автор", "publisher", Boolean.TRUE, "published", Boolean.FALSE, "status", " current "), "");

    Map<String, Object> directMetadata = byId(builder.buildRussianManifest(selection(directBook, derivedBook)), "direct").metadata();
    Map<String, Object> derivedMetadata = byId(builder.buildRussianManifest(selection(directBook, derivedBook)), "derived").metadata();
    assertEquals(List.of("Автор", "None"), directMetadata.get("authors"));
    assertEquals("True", directMetadata.get("publication"));
    assertEquals("False", directMetadata.get("readingStatus"));
    assertEquals("True · False", derivedMetadata.get("publication"));
    assertEquals(" current ", derivedMetadata.get("readingStatus"));
  }

  @Test
  void stringifiesYamlNativeBibliographyValuesLikePython() {
    Map<String, Object> compound = new LinkedHashMap<>();
    compound.put("x", 1);
    compound.put("enabled", true);
    compound.put("missing", null);
    Note exponent = note("bibliography/Exponent.md", "Exponent", "exponent", "bibliography", "book",
        Map.of("author", "Автор", "publication", 1e20), "");
    Note list = note("bibliography/List.md", "List", "list", "bibliography", "book",
        Map.of("author", "Автор", "publication", Arrays.asList(1, "a", true, null)), "");
    Note map = note("bibliography/Map.md", "Map", "map", "bibliography", "book",
        Map.of("author", "Автор", "publication", compound), "");

    var result = builder.buildRussianManifest(selection(exponent, list, map));
    assertEquals("1e+20", byId(result, "exponent").metadata().get("publication"));
    assertEquals("[1, 'a', True, None]", byId(result, "list").metadata().get("publication"));
    assertEquals("{'x': 1, 'enabled': True, 'missing': None}", byId(result, "map").metadata().get("publication"));
  }

  @Test
  void appliesPythonTruthinessToTitlesAndClaimStatementDescriptions() {
    Note falseTitle = note("blog/False.md", "Fallback false", "false-title", "blog", "essay", Map.of("title", false), "");
    Note zeroTitle = note("blog/Zero.md", "Fallback zero", "zero-title", "blog", "essay", Map.of("title", 0), "");
    Note claim = note("claims/Claim.md", "Claim", "claim", "blog", "claim", Map.of(
        "statement", false, "description", "Описание тезиса."), "");

    var result = builder.buildRussianManifest(selection(falseTitle, zeroTitle, claim));
    assertEquals("Fallback false", byId(result, "false-title").metadata().get("title"));
    assertEquals("Fallback zero", byId(result, "zero-title").metadata().get("title"));
    assertEquals("Описание тезиса.", byId(result, "claim").metadata().get("description"));
    assertEquals("Описание тезиса.", byId(result, "claim").metadata().get("statement"));
  }

  @Test
  void appliesHtml5NumericReferenceReplacementRulesInBookDescriptions() {
    Note book = note("bibliography/Book.md", "Book", "book", "bibliography", "book", Map.of("author", "Автор"),
        "<div class=\"book-description\"><p>&#0;|&#128;|&#x80;|&#55296;|&#xD800;|&#57344;|&#1114112;|&#x110000;|&#x1F600;</p></div>");

    assertEquals("\uFFFD|€|€|\uFFFD|\uFFFD|\uE000|\uFFFD|\uFFFD|😀",
        only(builder.buildRussianManifest(selection(book))).metadata().get("description"));
  }

  @Test
  void normalizesPythonWhitespaceAndOversizedNumericReferencesInBookDescriptions() {
    String body = "<div class=\"book-description\"><p>A\u0085B&#999999999999999999999999999999;|&#xFFFFFFFFFFFFFFFFFFFFFFFF;</p></div>";
    Note encoded = note("bibliography/Book.md", "Book", "book", "bibliography", "book", Map.of("author", "Автор"), body);
    Note decoded = note("bibliography/Book.md", "Book", "book", "bibliography", "book",
        Map.of("author", "Автор", "description", "A B\uFFFD|\uFFFD"), "");

    var encodedEntry = only(builder.buildRussianManifest(selection(encoded)));
    var decodedEntry = only(builder.buildRussianManifest(selection(decoded)));
    assertEquals("A B\uFFFD|\uFFFD", encodedEntry.metadata().get("description"));
    assertEquals(decodedEntry.metadata().get("sourceHash"), encodedEntry.metadata().get("sourceHash"));
  }

  @Test
  void omitsExplicitNullCollectionMetadata() {
    Map<String, Object> musicExtra = nullableMap("artist", "Artist", "albumTitle", "Album", "format", null, "streamingUrl", null, "bandcampEmbedUrl", null);
    Note musicWithNulls = note("reviews/Album.md", "Album", "album", "music", "album", musicExtra, "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь.");
    Note musicWithoutOptionals = note("reviews/Album.md", "Album", "album", "music", "album", Map.of("artist", "Artist", "albumTitle", "Album"), "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь.");
    Map<String, Object> musicMetadata = only(builder.buildRussianManifest(selection(musicWithNulls))).metadata();
    assertEquals(only(builder.buildRussianManifest(selection(musicWithoutOptionals))).metadata(), musicMetadata);
    for (String field : List.of("format", "streamingUrl", "bandcampEmbedUrl")) {
      assertFalse(musicMetadata.containsKey(field));
    }

    Map<String, Object> bookExtra = nullableMap("author", "Автор", "readingStatus", null, "status", "reading", "use", null, "boundary", null, "selectedQuote", null);
    Map<String, Object> bookMetadata = only(builder.buildRussianManifest(selection(
        note("bibliography/Book.md", "Книга", "book", "bibliography", "book", bookExtra, "")))).metadata();
    assertEquals("reading", bookMetadata.get("readingStatus"));
    for (String field : List.of("use", "boundary", "selectedQuote")) {
      assertFalse(bookMetadata.containsKey(field));
    }

    Map<String, Object> claimExtra = nullableMap("statement", "Тезис.", "supports", null, "sources", null);
    Map<String, Object> claimMetadata = only(builder.buildRussianManifest(selection(
        note("claims/Claim.md", "Тезис", "claim", "blog", "claim", claimExtra, "")))).metadata();
    assertFalse(claimMetadata.containsKey("supports"));
    assertFalse(claimMetadata.containsKey("sources"));
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
  void reportsBodyLinkAmbiguityWithThePythonFieldPrefix() {
    Note source = note("blog/Source.md", "Источник", "source", "blog", "note", Map.of(), "[[Общее]]");
    Note first = note("blog/First.md", "Общее", "first", "blog", "note", Map.of(), "");
    Note second = note("blog/Second.md", "Общее", "second", "blog", "note", Map.of(), "");

    ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(source, first, second)));
    assertEquals("link Общее", error.fieldName());
    assertEquals("is ambiguous; use a publicId or vault path", error.reason());
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
  void acceptsAnyPositiveIntegralReadTimeAndRejectsOtherNumbers() {
    for (Number value : List.of(45L, new BigInteger("90"))) {
      Note note = note("blog/Essay.md", "Essay", "essay", "blog", "essay", Map.of("readTime", value), "");
      assertEquals(value, only(builder.buildRussianManifest(selection(note))).metadata().get("readTime"));
    }
    for (Number value : List.of(0, -1L, 1.5d)) {
      Note note = note("blog/Invalid.md", "Essay", "invalid", "blog", "essay", Map.of("readTime", value), "");
      ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
          () -> builder.buildRussianManifest(selection(note)));
      assertEquals("readTime", error.fieldName());
      assertEquals("must be a positive integer", error.reason());
    }
  }

  @Test
  void acceptsPythonIsoDateFormsWithoutRewritingMetadata() {
    for (String date : List.of("20260723", "2026-W30-4", "2026W304", "2026-W30", "2026W30", "0001-01-01", "00010101", "0001-W01-1")) {
      Note common = note("blog/" + date + ".md", "Дата", "date-" + date.replaceAll("[^0-9]", ""), "blog", "essay", Map.of("date", date), "");
      assertEquals(date, only(builder.buildRussianManifest(selection(common))).metadata().get("date"));

      Note now = note("editorial/Now.md", "Сейчас", "now", "editorial", "curated_page", Map.of(
          "editorialPage", "now", "date", date, "status", "current"), editorialNowBody());
      assertEquals(date, only(builder.buildRussianManifest(selection(now))).metadata().get("date"));
    }

    ManifestBuilder.ManifestValidationException invalid = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(note("blog/Ordinal.md", "Дата", "ordinal", "blog", "essay", Map.of("date", "2026-204"), ""))));
    assertEquals("date", invalid.fieldName());
    assertEquals("must be a real YYYY-MM-DD date", invalid.reason());

    for (String malformed : List.of("2026W30-4", "2026-W304", "0000-01-01", "00000101", "0000-W01-1")) {
      ManifestBuilder.ManifestValidationException malformedError = assertThrows(ManifestBuilder.ManifestValidationException.class,
          () -> builder.buildRussianManifest(selection(note("blog/Invalid.md", "Дата", "invalid", "blog", "essay", Map.of("date", malformed), ""))));
      assertEquals("date", malformedError.fieldName());
    }

    for (String malformed : List.of("0000-01-01", "00000101", "0000-W01-1")) {
      Note now = note("editorial/Now.md", "Сейчас", "now", "editorial", "curated_page", Map.of(
          "editorialPage", "now", "date", malformed, "status", "current"), editorialNowBody());
      assertEquals("date", assertThrows(ManifestBuilder.ManifestValidationException.class,
          () -> builder.buildRussianManifest(selection(now))).fieldName());
    }

    Note malformedWikilink = note("editorial/Now.md", "Сейчас", "now", "editorial", "curated_page", Map.of(
        "editorialPage", "now", "date", "[[|2026-07-23]]", "status", "current"), editorialNowBody());
    ManifestBuilder.ManifestValidationException malformedWikilinkError = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(malformedWikilink)));
    assertEquals("date", malformedWikilinkError.fieldName());
    assertEquals("must be a real YYYY-MM-DD date", malformedWikilinkError.reason());
  }

  @Test
  void emitsEntriesInSourcePathOrder() {
    var result = builder.buildRussianManifest(selection(note("z/Second.md", "Second", "second", "blog", "note", Map.of(), ""), note("a/First.md", "First", "first", "blog", "note", Map.of(), "")));
    assertEquals(List.of("a/First.md", "z/Second.md"), result.entries().stream().map(entry -> entry.sourcePath()).toList());
  }

  @Test
  void ordersEntryPathsAndAssetsByUnicodeCodePoint() {
    Note source = note("z/Source.md", "Source", "source", "blog", "note", Map.of(),
        "![[😀/asset.png]] ![[💡/asset.png]] ![[𐐀/asset.png]] ![[\uE000/asset.png]] ![[a/asset.png]]");
    Note smile = note("😀/x.md", "Smile", "smile", "blog", "note", Map.of(), "");
    Note bulb = note("💡/x.md", "Bulb", "bulb", "blog", "note", Map.of(), "");
    Note deseret = note("𐐀/x.md", "Deseret", "deseret", "blog", "note", Map.of(), "");
    Note bmp = note("\uE000/x.md", "Bmp", "bmp", "blog", "note", Map.of(), "");
    Note ascii = note("a/x.md", "Ascii", "ascii", "blog", "note", Map.of(), "");

    var result = builder.buildRussianManifest(selection(source, smile, bulb, deseret, bmp, ascii));
    assertEquals(List.of("a/x.md", "z/Source.md", "\uE000/x.md", "𐐀/x.md", "💡/x.md", "😀/x.md"),
        result.entries().stream().map(entry -> entry.sourcePath()).toList());
    assertEquals(List.of("a/asset.png", "\uE000/asset.png", "𐐀/asset.png", "💡/asset.png", "😀/asset.png"), result.assets());
  }

  @Test
  void ignoresFalsyFrontmatterTitlesWhenResolvingDescriptiveReferences() {
    Note source = note("claims/Source.md", "Source", "source", "blog", "claim", Map.of(
        "statement", "Тезис.", "supports", List.of("[[False]]", "[[0]]", "[[Actual false]]", "[[zero-id]]")), "");
    Note falseTitle = note("blog/Target.md", "Actual false", "false-id", "blog", "note", Map.of("title", false), "");
    Note zeroTitle = note("blog/Other.md", "Actual zero", "zero-id", "blog", "note", Map.of("title", 0), "");

    var result = builder.buildRussianManifest(selection(source, falseTitle, zeroTitle));
    assertEquals(List.of("False", "0"), result.strippedLinks().stream().map(ManifestLink::target).toList());
    assertEquals(List.of("Actual false", "zero-id"), result.retainedLinks().stream().map(ManifestLink::target).toList());
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
  void resolvesTrimmedAliasesForShowcaseCurrentCardsAndClaimReferences() {
    Note essays = note("editorial/Essays.md", "Эссе", "essays", "editorial", "curated_page", Map.of("editorialPage", "essays"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nТексты.\n\n## Принцип списка\n\nПринцип.\n\nПодсказка поиска:: Искать\n\n## Витрина\n\n### [[Shared essay]]\n\nТекст.");
    Note essay = note("blog/Essay.md", "Другое эссе", "essay-target", "blog", "essay", Map.of(), "", List.of(" Shared essay "));

    Note home = note("editorial/Home.md", "Главная", "home", "editorial", "curated_page", Map.of("editorialPage", "home"),
        homeWithLinkedCurrentCards().replace("[[Study source|Текущий фокус]]", "[[Shared study|Текущий фокус]]"));
    Note study = note("blog/Study.md", "Другая учеба", "study-target", "blog", "essay", Map.of(), "", List.of(" Shared study "));
    Note build = note("concepts/Build.md", "Build source", "build-target", "concepts", "concept", Map.of("description", "Описание."), "## Определение\n\nОпределение.");
    Note book = note("bibliography/Book.md", "Book source", "book-target", "bibliography", "book", Map.of("author", "Автор"), "");
    Note album = note("reviews/Album.md", "Album source", "album-target", "music", "album", Map.of("artist", "Автор", "albumTitle", "Альбом"), "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь.");

    Note claim = note("claims/Claim.md", "Тезис", "claim", "blog", "claim", Map.of(
        "statement", "Тезис.", "supports", List.of("[[Shared claim]]")), "");
    Note relation = note("claims/Relation.md", "Другая связь", "claim-target", "blog", "claim", Map.of("statement", "Связь."), "", List.of(" Shared claim "));

    var result = builder.buildRussianManifest(selection(essays, essay, home, study, build, book, album, claim, relation));
    assertEquals(List.of("essay-target"), byId(result, "essays").metadata().get("pinned"));
    assertEquals("study-target", ((List<Map<String, Object>>) byId(result, "home").metadata().get("current")).getFirst().get("target"));
    assertEquals(Map.of("label", "Shared claim", "target", "claim-target"),
        ((List<Map<String, Object>>) byId(result, "claim").metadata().get("supports")).getFirst());
  }

  @Test
  void recordsHomeCurrentTargetBeforeItsProseLinks() {
    Note home = note("editorial/Home.md", "Главная", "home", "editorial", "curated_page", Map.of("editorialPage", "home"),
        homeWithLinkedCurrentCards().replaceFirst("Описание\\.", "См. [[Public note]]."));
    Note study = note("blog/Study.md", "Study source", "study-target", "blog", "essay", Map.of(), "");
    Note build = note("concepts/Build.md", "Build source", "build-target", "concepts", "concept", Map.of("description", "Описание."), "## Определение\n\nОпределение.");
    Note book = note("bibliography/Book.md", "Book source", "book-target", "bibliography", "book", Map.of("author", "Автор"), "");
    Note album = note("reviews/Album.md", "Album source", "album-target", "music", "album", Map.of("artist", "Автор", "albumTitle", "Альбом"), "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь.");
    Note publicNote = note("blog/Public.md", "Public note", "public-note", "blog", "note", Map.of(), "");

    var result = builder.buildRussianManifest(selection(home, study, build, book, album, publicNote));
    assertEquals(List.of("Study source", "Public note"), result.retainedLinks().subList(0, 2).stream().map(ManifestLink::target).toList());
    assertEquals(List.of("editorial", "editorial-text"), result.retainedLinks().subList(0, 2).stream().map(ManifestLink::kind).toList());
  }

  @Test
  void recordsHomeLinksInMetadataOrderBeforeCurrentCardLinks() {
    String body = homeWithLinkedCurrentCards()
        .replace("Кратко.", "[[Summary target]] [[Summary private]]")
        .replace("Заголовок.", "[[Hero target]]")
        .replaceFirst("Описание\\.", "[[Current prose target]] [[Current prose private]]");
    Note home = note("editorial/Home.md", "Главная", "home", "editorial", "curated_page", Map.of("editorialPage", "home"), body);
    Note summary = note("blog/Summary.md", "Summary target", "summary", "blog", "note", Map.of(), "");
    Note hero = note("blog/Hero.md", "Hero target", "hero", "blog", "note", Map.of(), "");
    Note study = note("blog/Study.md", "Study source", "study-target", "blog", "essay", Map.of(), "");
    Note prose = note("blog/Prose.md", "Current prose target", "current-prose", "blog", "note", Map.of(), "");
    Note build = note("concepts/Build.md", "Build source", "build-target", "concepts", "concept", Map.of("description", "Описание."), "## Определение\n\nОпределение.");
    Note book = note("bibliography/Book.md", "Book source", "book-target", "bibliography", "book", Map.of("author", "Автор"), "");
    Note album = note("reviews/Album.md", "Album source", "album-target", "music", "album", Map.of("artist", "Автор", "albumTitle", "Альбом"), "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь.");

    var result = builder.buildRussianManifest(selection(home, summary, hero, study, prose, build, book, album));
    assertEquals(List.of("Summary target", "Hero target", "Study source", "Current prose target", "Build source", "Book source", "Album source"),
        result.retainedLinks().stream().map(ManifestLink::target).toList());
    assertEquals(List.of("Summary private", "Current prose private"), result.strippedLinks().stream().map(ManifestLink::target).toList());
  }

  @Test
  void recordsEditorialLinksInPythonFieldOrderForAboutAndNow() {
    Note about = note("editorial/About.md", "О проекте", "about", "editorial", "curated_page", Map.of("editorialPage", "about"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nО проекте.\n\n## Лид\n\n[[Lead target]] [[Lead private]]\n\n## Принципы\n\n### Принцип\n\n[[Principle target]] [[Principle private]]\n\n## Колофон\n\n[[Colophon target]] [[Colophon private]]");
    Note now = note("editorial/Now.md", "Сейчас", "now", "editorial", "curated_page", Map.of(
        "editorialPage", "now", "date", "2026-07-15", "status", "current"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nСейчас.\n\n## Обновлено\n\nСегодня.\n\n## Читаю\n\n### [[Title target]]\n\n[[Text target]] [[Text private]]\n\nДействие вопроса:: [[Question target]]\nДействие записи:: [[Listening target]]");
    Note lead = note("blog/Lead.md", "Lead target", "lead", "blog", "note", Map.of(), "");
    Note principle = note("blog/Principle.md", "Principle target", "principle", "blog", "note", Map.of(), "");
    Note colophon = note("blog/Colophon.md", "Colophon target", "colophon", "blog", "note", Map.of(), "");
    Note title = note("blog/Title.md", "Title target", "title", "blog", "note", Map.of(), "");
    Note text = note("blog/Text.md", "Text target", "text", "blog", "note", Map.of(), "");
    Note question = note("blog/Question.md", "Question target", "question", "blog", "note", Map.of(), "");
    Note listening = note("blog/Listening.md", "Listening target", "listening", "blog", "note", Map.of(), "");

    var result = builder.buildRussianManifest(selection(about, now, lead, principle, colophon, title, text, question, listening));
    assertEquals(List.of("Lead target", "Principle target", "Colophon target", "Title target", "Text target", "Question target", "Listening target"),
        result.retainedLinks().stream().map(ManifestLink::target).toList());
    assertEquals(List.of("Lead private", "Principle private", "Colophon private", "Text private"),
        result.strippedLinks().stream().map(ManifestLink::target).toList());
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
  void reportsAmbiguousCurrentTargetsAsEditorialTextLinks() {
    Note home = note("editorial/Home.md", "Главная", "home", "editorial", "curated_page", Map.of("editorialPage", "home"),
        homeWithLinkedCurrentCards().replace("[[Study source|Текущий фокус]]", "[[Общее|Текущий фокус]]"));
    Note first = note("blog/First.md", "Общее", "first", "blog", "essay", Map.of(), "");
    Note second = note("blog/Second.md", "Общее", "second", "blog", "essay", Map.of(), "");

    ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(home, first, second)));
    assertEquals("editorial text link Общее", error.fieldName());
    assertEquals("is ambiguous; use a publicId or vault path", error.reason());

    Note unresolved = note("editorial/Home.md", "Главная", "home", "editorial", "curated_page", Map.of("editorialPage", "home"),
        homeWithLinkedCurrentCards().replace("[[Study source|Текущий фокус]]", "[[Неизвестно|Текущий фокус]]"));
    ManifestBuilder.ManifestValidationException unresolvedError = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(unresolved)));
    assertEquals("current[0].target", unresolvedError.fieldName());
    assertEquals("must resolve to exactly one published entry", unresolvedError.reason());
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
  void resolvesShowcaseTargetsAndProseLinksWithEditorialTextProvenance() {
    Note essays = note("editorial/Essays.md", "Essays", "essays", "editorial", "curated_page", Map.of("editorialPage", "essays"),
        "## Кратко\n\nЭссе.\n\n## Eyebrow\n\nТексты\n\n## Принцип списка\n\nПолный список.\n\nПодсказка поиска:: Искать\n\n## Витрина\n\n### [[Essay One]]\n\nНачать с [[Essay Two]].");
    Note first = note("blog/Essay One.md", "Essay One", "essay-one", "blog", "essay", Map.of(), "");
    Note second = note("blog/Essay Two.md", "Essay Two", "essay-two", "blog", "essay", Map.of(), "");

    var result = builder.buildRussianManifest(selection(essays, first, second));
    Map<String, Object> metadata = byId(result, "essays").metadata();
    assertEquals(List.of("essay-one"), metadata.get("pinned"));
    assertEquals(List.of(Map.of(
        "target", "essay-one",
        "text", List.of(
            Map.of("kind", "text", "value", "Начать с "),
            Map.of("kind", "reference", "target", "essay-two"),
            Map.of("kind", "text", "value", ".")))), metadata.get("showcase"));
    assertTrue(result.retainedLinks().stream().anyMatch(link -> link.kind().equals("editorial-text")
        && link.target().equals("Essay Two")));
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
  void keepsConceptPrimaryReferencesStructuralUntilEditorialFiltering() {
    Note concepts = note("editorial/Concepts.md", "Концепты", "concepts", "editorial", "curated_page", Map.of("editorialPage", "concepts"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nКонцепты.\n\n## Базовый концепт\n\nМетка:: Базовый\nМатериал:: [[private-id]]");

    var result = builder.buildRussianManifest(selection(concepts));
    assertEquals(List.of(new ManifestLink("editorial/Concepts.md", "[[private-id]]", "editorial")), result.strippedLinks());
    assertFalse(result.strippedLinks().stream().anyMatch(link -> link.kind().equals("editorial-text")));
  }

  @Test
  void rejectsShowcaseItemsWithUnexpectedKeys() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", "library");
    metadata.put("showcase", List.of(Map.of("target", "book", "text", "Текст.", "route", "book")));
    metadata.put("pinned", List.of("book"));

    ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> ManifestBuilder.resolvePins("editorial/Library.md", metadata, List.of(referenceNote("book"))));
    assertEquals("showcase[0]", error.fieldName());
    assertEquals("must contain exactly target and text", error.reason());
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
  void stringifiesNonFiniteScalarsLikePythonAndHashesTheirRenderedValues() {
    Note nonFiniteBook = note("bibliography/Book.md", "Книга", "book", "bibliography", "book", Map.of(
        "author", "Автор",
        "publication", Map.of("value", Double.NaN),
        "readingStatus", List.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)), "");
    Note renderedBook = note("bibliography/Book.md", "Книга", "book", "bibliography", "book", Map.of(
        "author", "Автор",
        "publication", "{'value': nan}",
        "readingStatus", "[inf, -inf]"), "");
    Note scalarCommon = note("blog/Scalar.md", "Fallback", "scalar", "blog", "essay", Map.of(
        "title", Double.NaN, "description", Double.POSITIVE_INFINITY), "");

    var nonFiniteMetadata = only(builder.buildRussianManifest(selection(nonFiniteBook))).metadata();
    var renderedMetadata = only(builder.buildRussianManifest(selection(renderedBook))).metadata();
    var commonMetadata = only(builder.buildRussianManifest(selection(scalarCommon))).metadata();
    assertEquals("{'value': nan}", nonFiniteMetadata.get("publication"));
    assertEquals("[inf, -inf]", nonFiniteMetadata.get("readingStatus"));
    assertEquals(renderedMetadata.get("sourceHash"), nonFiniteMetadata.get("sourceHash"));
    assertEquals("nan", commonMetadata.get("title"));
    assertEquals("inf", commonMetadata.get("description"));
  }

  @Test
  void preservesBibliographyReadingStatusWhitespace() {
    Map<String, Object> direct = only(builder.buildRussianManifest(selection(
        note("bibliography/Book.md", "Книга", "book", "bibliography", "book", Map.of("author", "Автор", "readingStatus", "  читаю  "), "")))).metadata();
    Map<String, Object> fallback = only(builder.buildRussianManifest(selection(
        note("bibliography/Book.md", "Книга", "book", "bibliography", "book", Map.of("author", "Автор", "status", "  отложено  "), "")))).metadata();
    assertEquals("  читаю  ", direct.get("readingStatus"));
    assertEquals("  отложено  ", fallback.get("readingStatus"));
  }

  @Test
  void distinguishesNonStringAndMalformedMusicUrlDiagnostics() {
    ManifestBuilder.ManifestValidationException nonString = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(note("reviews/Album.md", "Album", "album", "music", "album", Map.of("artist", "Artist", "albumTitle", "Album", "streamingUrl", 42), "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь."))));
    assertEquals("streamingUrl", nonString.fieldName());
    assertEquals("must be a URL string", nonString.reason());

    ManifestBuilder.ManifestValidationException malformed = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(note("reviews/Album.md", "Album", "album", "music", "album", Map.of("artist", "Artist", "albumTitle", "Album", "streamingUrl", "ftp://invalid"), "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь."))));
    assertEquals("streamingUrl", malformed.fieldName());
    assertEquals("must be an http(s) URL", malformed.reason());

    for (String invalid : List.of("https://?query", "https://#fragment")) {
      ManifestBuilder.ManifestValidationException malformedAuthority = assertThrows(ManifestBuilder.ManifestValidationException.class,
          () -> builder.buildRussianManifest(selection(note("reviews/Album.md", "Album", "album", "music", "album", Map.of("artist", "Artist", "albumTitle", "Album", "streamingUrl", invalid), "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь."))));
      assertEquals("streamingUrl", malformedAuthority.fieldName());
      assertEquals("must be an http(s) URL", malformedAuthority.reason());
    }

    assertEquals("https://example.com/path", only(builder.buildRussianManifest(selection(
        note("reviews/Album.md", "Album", "album", "music", "album", Map.of("artist", "Artist", "albumTitle", "Album", "streamingUrl", "https://example.com/path"), "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь.")))).metadata().get("streamingUrl"));
    assertEquals("https://exa mple.com/path", only(builder.buildRussianManifest(selection(
        note("reviews/Album.md", "Album", "album", "music", "album", Map.of("artist", "Artist", "albumTitle", "Album", "streamingUrl", "https://exa mple.com/path"), "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь.")))).metadata().get("streamingUrl"));
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
  void hashesNestedIntegerAndDecimalMetadataLikePythonJson() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("sources", List.of(Map.of("page", 12, "weight", 1.25)));
    metadata.put("selectedQuote", Map.of("text", "Цитата.", "score", 0.5));

    assertEquals("c985ffddbbee1e97b5ac82ca893d6e53860dac96bd2d541dcb0e2116be4f8770",
        ManifestBuilder.sourceHash(metadata, "Тело."));
  }

  @Test
  void hashesExponentAndNonFiniteNumbersLikePythonJson() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("nested", Map.of(
        "tiny", List.of(1e-7, 1e-6, 1e-5, 1.25e-7),
        "large", 1e20,
        "nonFinite", List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)));

    assertEquals("e776f47ec71acbadf3a0e58f902d224c48a2affd9340bf235d0b19d755fec93c",
        ManifestBuilder.sourceHash(metadata, "Тело."));
  }

  @Test
  void hashesFixedAndExponentFloatBoundariesLikePythonJson() {
    Map<String, Object> metadata = Map.of("values", List.of(
        1e14, 1e15, 1e16, 1e20, 999999999999999.9,
        1234567890123456.0, 1234567890123456.8));

    assertEquals("60df887f4e156784152cb2d3ce670c81a05fb510b75750658d38b5996c908beb",
        ManifestBuilder.sourceHash(metadata, "Тело."));
  }

  @Test
  void hashesSubnormalFloatsLikePythonJson() {
    assertEquals("5c45a1d1519416239839d6f276a8fbcd753c49a159b94250a3f7467a8e4a3642",
        ManifestBuilder.sourceHash(Map.of("x", Double.MIN_VALUE), "Тело."));
  }

  @Test
  void sortsSourceHashObjectKeysByUnicodeCodePoint() {
    Map<String, Object> controllerProbe = new LinkedHashMap<>();
    controllerProbe.put("😀", 1);
    controllerProbe.put("a", 2);
    controllerProbe.put("💡", 3);
    controllerProbe.put("𐐀", 4);
    assertEquals("84fae55a07de006e3affcd3e7e41dcb665aa2133857aa73192955bef9a18690d",
        ManifestBuilder.sourceHash(controllerProbe, "Тело."));

    controllerProbe.put("\uE000", 5);
    assertEquals("5caacaad90afd1b3b86e7feb2ae965a42d30237a0205bc1feeadafc10fdd7f5b",
        ManifestBuilder.sourceHash(controllerProbe, "Тело."));
  }

  @Test
  void choosesPythonStringQuotesForCompoundPublicationValues() {
    Map<String, Object> publication = new LinkedHashMap<>();
    publication.put("text", "don't");
    Note rendered = note("bibliography/Book.md", "Book", "book", "bibliography", "book",
        Map.of("author", "Автор", "publication", publication), "");
    Note explicit = note("bibliography/Book.md", "Book", "book", "bibliography", "book",
        Map.of("author", "Автор", "publication", "{'text': \"don't\"}"), "");

    var renderedEntry = only(builder.buildRussianManifest(selection(rendered)));
    var explicitEntry = only(builder.buildRussianManifest(selection(explicit)));
    assertEquals("{'text': \"don't\"}", renderedEntry.metadata().get("publication"));
    assertEquals(explicitEntry.metadata().get("sourceHash"), renderedEntry.metadata().get("sourceHash"));
  }

  @Test
  void usesValidAlternativeFieldsWhenPrimaryValuesAreMalformed() {
    Note claim = note("claims/Claim.md", "Claim", "claim", "blog", "claim", Map.of(
        "statement", 42, "description", "Valid statement."), "");
    Note book = note("bibliography/Book.md", "Book", "book", "bibliography", "book", Map.of(
        "authors", List.of(42), "author", "Valid author"), "");
    Note album = note("reviews/Album.md", "Album", "album", "music", "album", Map.of(
        "artist", "Artist", "work", 42, "albumTitle", "Valid album"),
        "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь.");

    var result = builder.buildRussianManifest(selection(claim, book, album));
    assertEquals("Valid statement.", byId(result, "claim").metadata().get("statement"));
    assertEquals(List.of("Valid author"), byId(result, "book").metadata().get("authors"));
    assertEquals("Valid album", byId(result, "album").metadata().get("work"));
  }

  @Test
  void blocksInvalidFoundationalAndMissingMusicArtist() {
    ManifestBuilder.ManifestValidationException foundational = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(note("blog/Essay.md", "Essay", "essay", "blog", "essay", Map.of("foundational", "yes"), ""))));
    assertEquals("foundational", foundational.fieldName());
    assertEquals("must be a boolean", foundational.reason());

    ManifestBuilder.ManifestValidationException artist = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(note("reviews/Album.md", "Album", "album", "music", "album", Map.of("albumTitle", "Album"),
            "## Контекст записи\n\nКонтекст.\n\n## Личная связь\n\nСвязь."))));
    assertEquals("artist", artist.fieldName());
    assertEquals("must be a non-empty string", artist.reason());
  }

  @Test
  void filtersEditorialReferencesBeforeComputingTheFinalSourceHash() {
    Note concepts = note("editorial/Concepts.md", "Концепты", "concepts", "editorial", "curated_page", Map.of("editorialPage", "concepts"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nКонцепты.\n\n## Базовый концепт\n\nМетка:: База\nМатериал:: prototype-only");

    var result = builder.buildRussianManifest(selection(concepts));
    var entry = only(result);
    Map<String, Object> metadataWithoutHash = new LinkedHashMap<>(entry.metadata());
    Object sourceHash = metadataWithoutHash.remove("sourceHash");
    assertFalse(entry.metadata().containsKey("primary"));
    assertFalse(entry.metadata().toString().contains("prototype-only"));
    assertEquals(ManifestBuilder.sourceHash(metadataWithoutHash, entry.body()), sourceHash);
    assertEquals(List.of("prototype-only"), result.strippedLinks().stream().map(ManifestLink::target).toList());
    assertEquals(List.of("editorial"), result.strippedLinks().stream().map(ManifestLink::kind).toList());
  }

  @Test
  void keepsConceptPrimaryLabelWhenNoMaterialIsPublishedAndRejectsMalformedMaterial() {
    Note noMaterial = note("editorial/Concepts.md", "Концепты", "concepts", "editorial", "curated_page", Map.of("editorialPage", "concepts"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nКонцепты.\n\n## Базовый концепт\n\nМетка:: База");
    Map<String, Object> metadata = only(builder.buildRussianManifest(selection(noMaterial))).metadata();
    assertEquals("База", metadata.get("primaryLabel"));
    assertFalse(metadata.containsKey("primary"));

    Note malformed = note("editorial/Concepts.md", "Концепты", "concepts", "editorial", "curated_page", Map.of("editorialPage", "concepts"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nКонцепты.\n\n## Базовый концепт\n\nМетка:: База\nМатериал::");
    ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(malformed)));
    assertEquals("primary", error.fieldName());
    assertEquals("inline field `Материал::` must be non-empty", error.reason());
  }

  @Test
  void blocksTheWholeManifestWhenAnotherSelectedNoteHasAnUnsupportedTopic() {
    Note valid = note("blog/Valid.md", "Valid", "valid", "blog", "essay", Map.of(), "");
    Note invalid = note("blog/Invalid.md", "Invalid", "invalid", "blog", "essay", Map.of("topics", List.of("unsupported")), "");

    ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(valid, invalid)));
    assertEquals("blog/Invalid.md", error.sourcePath());
    assertEquals("topics", error.fieldName());
    assertEquals("contains unsupported values: unsupported", error.reason());
  }

  @Test
  void sortsUnsupportedTopicDiagnosticsByUnicodeCodePoint() {
    Note invalid = note("blog/Invalid.md", "Invalid", "invalid", "blog", "essay", Map.of(
        "topics", List.of("😀", "💡", "𐐀", "\uE000")), "");

    ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(invalid)));
    assertEquals("contains unsupported values: \uE000, 𐐀, 💡, 😀", error.reason());
  }

  @Test
  void exportsExplicitBookDescriptionAndKonspektBodyOnly() {
    Note book = note("bibliography/Book.md", "Book", "book", "bibliography", "book", Map.of("author", "Автор", "description", "Краткое описание."),
        "<div class=\"book-description\"><p>Старое описание.</p></div>\n\n## Конспект\n\n### Введение\n\nПервый пункт.\n\n### Старт\n\n- Второй пункт.\n\n## Blinks\n\nНе экспортировать.");
    var entry = only(builder.buildRussianManifest(selection(book)));
    assertEquals("Краткое описание.", entry.metadata().get("description"));
    assertEquals("### Введение\n\nПервый пункт.\n\n### Старт\n\n- Второй пункт.", entry.body());
    assertFalse(entry.body().contains("Blinks"));
  }

  @Test
  void stripsVisibleObsidianCommentsButPreservesProtectedCodeMarkers() {
    Note music = note("reviews/Album.md", "Album", "album", "music", "album", Map.of("artist", "Artist", "albumTitle", "Album"),
        "## Контекст записи\n\nВидимый %%PRIVATE_CONTEXT%% текст с `%%INLINE_CODE%%`.\n\n```text\n%%FENCED_CODE%%\n```\n\n## Личная связь\n\nВидимая %%PRIVATE_ASSOCIATION%% связь.");
    var entry = only(builder.buildRussianManifest(selection(music)));
    assertFalse((entry.metadata().toString() + entry.body()).contains("PRIVATE_"));
    assertTrue(((String) entry.metadata().get("context")).contains("%%INLINE_CODE%%"));
    assertTrue(((String) entry.metadata().get("context")).contains("%%FENCED_CODE%%"));
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
  void recordsClaimProvenanceBeforeGenericFrontmatterLinks() {
    Note claim = note("claims/Claim.md", "Тезис", "claim", "blog", "claim", Map.of(
        "statement", "Тезис.", "supports", List.of("[[Связь]]"),
        "sources", List.of(Map.of("link", "[[Приватный источник]]")),
        "links", List.of("front-id", "private-front")), "");
    Note relation = note("claims/Relation.md", "Связь", "relation", "blog", "claim", Map.of("statement", "Связь."), "");
    Note front = note("blog/Front.md", "Фронт", "front-id", "blog", "note", Map.of(), "");

    var result = builder.buildRussianManifest(selection(claim, relation, front));
    assertEquals(List.of("Связь", "front-id"), result.retainedLinks().stream().map(ManifestLink::target).toList());
    assertEquals(List.of("Приватный источник", "private-front"), result.strippedLinks().stream().map(ManifestLink::target).toList());
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

  @Test
  void rejectsDuplicateClaimsShowcasePinsAtTheLaterTarget() {
    Note claims = note("editorial/Claims.md", "Тезисы", "claims", "editorial", "curated_page", Map.of("editorialPage", "claims"),
        "## Кратко\n\nКратко.\n\n## Eyebrow\n\nТезисы.\n\n## Витрина\n\n### [[claim-target]]\n\nПервый.\n\n### [[claim-target]]\n\nВторой.");
    Note target = note("claims/Target.md", "Claim target", "claim-target", "blog", "claim", Map.of("statement", "Тезис."), "");

    ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(claims, target)));
    assertEquals("showcase[1].target", error.fieldName());
    assertEquals("must not duplicate an earlier pin", error.reason());
  }

  @Test
  void hashesPythonSortedAndCoercedMapKeys() {
    Map<Object, Object> integers = new LinkedHashMap<>();
    integers.put(10, "a");
    integers.put(2, "b");
    assertEquals("486e4b611acd1acc1016fe6a196abaa8969c0660d7a52b5e88a7debfdc5b6674",
        sourceHashWithPythonKeys(integers, "Body."));

    Map<Object, Object> numeric = new LinkedHashMap<>();
    numeric.put(10, "a");
    numeric.put(2, "b");
    numeric.put(1.5d, "c");
    assertEquals("9e5c31212ef36b7d20432ae0eee2a8d0f7e7e30e3b8cff4cc4cea5b1877d3e99",
        sourceHashWithPythonKeys(numeric, "Body."));

    Map<Object, Object> nullOnly = new LinkedHashMap<>();
    nullOnly.put(null, "n");
    assertEquals("8f410064d35a8acef26c509f6d52788b19a23445a8f568cf859486acbc50b52d",
        sourceHashWithPythonKeys(nullOnly, "Body."));

    Map<Object, Object> mixed = new LinkedHashMap<>();
    mixed.put(null, "n");
    mixed.put(2, "b");
    assertThrows(IllegalStateException.class, () -> sourceHashWithPythonKeys(mixed, "Body."));
  }

  @Test
  void sanitizesEditorialAndBookBodiesBeforeMetadataAndHashing() {
    Note now = note("editorial/Now.md", "Сейчас", "now", "editorial", "curated_page", Map.of(
        "editorialPage", "now", "date", "2026-07-15", "status", "current"),
        editorialNowBody().replace("Текст.", "Visible %%PRIVATE_SECTION%% text with `%%INLINE_CODE%%`.\n\n```text\n%%FENCED_CODE%%\n```"));
    var nowEntry = only(builder.buildRussianManifest(selection(now)));
    assertSanitizedAndHashed(nowEntry);
    assertFalse(nowEntry.metadata().toString().contains("PRIVATE_SECTION"));
    assertTrue(nowEntry.metadata().toString().contains("%%INLINE_CODE%%"));
    assertTrue(nowEntry.metadata().toString().contains("%%FENCED_CODE%%"));

    Note book = note("bibliography/Book.md", "Book", "book", "bibliography", "book", Map.of("author", "Author"),
        "<div class=\"book-description\"><p>Central %%PRIVATE_BOOK%% idea with `%%INLINE_CODE%%`.</p></div>\n\n## Конспект\n\nVisible %%PRIVATE_BODY%% body.\n\n```text\n%%FENCED_CODE%%\n```");
    var bookEntry = only(builder.buildRussianManifest(selection(book)));
    assertSanitizedAndHashed(bookEntry);
    assertEquals("Central idea with `%%INLINE_CODE%%`.", bookEntry.metadata().get("description"));
    assertFalse(bookEntry.metadata().toString().contains("PRIVATE_BOOK"));
    assertFalse(bookEntry.body().contains("PRIVATE_BODY"));
    assertTrue(bookEntry.body().contains("%%FENCED_CODE%%"));
  }

  @Test
  void keepsUntypedEssayAndNoteBodiesExceptForPublicSanitationAndLinks() {
    String body = "## Наблюдение\n\nТекст %%PRIVATE%% с [[Target]].\n\n## Эксперимент\n\nЕщё один раздел.";
    Note target = note("blog/Target.md", "Target", "target", "blog", "note", Map.of(), "");
    for (String contentType : List.of("essay", "note")) {
      Note source = note("blog/Source.md", "Источник", contentType + "-source", "blog", contentType,
          Map.of("description", "Единственное общее резюме."), body);
      var entry = byId(builder.buildRussianManifest(selection(source, target)), contentType + "-source");
      assertEquals("Единственное общее резюме.", entry.metadata().get("description"));
      assertEquals("## Наблюдение\n\nТекст  с [Target](/ru/notes/target/).\n\n## Эксперимент\n\nЕщё один раздел.", entry.body());
      for (String field : List.of("abstract", "why", "sections", "closing", "sources", "observation", "model", "boundary", "experiment")) {
        assertFalse(entry.metadata().containsKey(field));
      }
    }
  }

  private static dev.eugene.astroexport.model.ManifestEntry only(dev.eugene.astroexport.model.ManifestResult result) { return result.entries().getFirst(); }
  private static dev.eugene.astroexport.model.ManifestEntry byId(dev.eugene.astroexport.model.ManifestResult result, String id) { return result.entries().stream().filter(entry -> id.equals(entry.metadata().get("id"))).findFirst().orElseThrow(); }
  private static SelectionResult selection(Note... notes) { return new SelectionResult(List.of(notes), List.of(), notes.length, notes.length); }
  private static Note note(String path, String title, String id, String collection, String type, Map<String, Object> extra, String body) {
    return note(path, title, id, collection, type, extra, body, List.of());
  }
  private static Note note(String path, String title, String id, String collection, String type, Map<String, Object> extra, String body, List<String> aliases) {
    Map<String, Object> metadata = new LinkedHashMap<>(); metadata.put("title", title); metadata.put("publish", true); metadata.put("publicId", id); metadata.put("publicCollection", collection); metadata.put("publicContentType", type); metadata.putAll(extra);
    return new Note(Path.of(path), path, title, metadata, body, true, id, collection, type, aliases);
  }
  private static Note referenceNote(String id) { return new Note(Path.of(id + ".md"), id + ".md", id, Map.of(), "", true, id, "blog", "note", List.of()); }
  private static Map<String, Object> nullableMap(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      result.put((String) values[index], values[index + 1]);
    }
    return result;
  }
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static String sourceHashWithPythonKeys(Map<Object, Object> metadata, String body) {
    return ManifestBuilder.sourceHash((Map) metadata, body);
  }
  private static void assertSanitizedAndHashed(dev.eugene.astroexport.model.ManifestEntry entry) {
    Map<String, Object> metadata = new LinkedHashMap<>(entry.metadata());
    Object sourceHash = metadata.remove("sourceHash");
    assertEquals(ManifestBuilder.sourceHash(metadata, entry.body()), sourceHash);
  }
  private void assertSelectedQuoteError(Object selectedQuote, String field) {
    ManifestBuilder.ManifestValidationException error = assertThrows(ManifestBuilder.ManifestValidationException.class,
        () -> builder.buildRussianManifest(selection(note("bibliography/Book.md", "Книга", "book", "bibliography", "book", Map.of("author", "Автор", "selectedQuote", selectedQuote), ""))));
    assertEquals(field, error.fieldName());
  }
  private static String homeWithLinkedCurrentCards() { return "## Кратко\n\nКратко.\n\n## Eyebrow\n\nГлавная.\n\n## Hero\n\n### Заголовок\n\nЗаголовок.\n\n### Лид\n\nЛид.\n\n### Описание изображения\n\nAlt.\n\n## Сейчас\n\n### Изучаю\n\n[[Study source|Текущий фокус]]\n\nОписание.\n\n### Создаю\n\n[[Build source|Текущая сборка]]\n\nОписание.\n\n### Читаю\n\n[[Book source|Текущая книга]]\n\nОписание.\n\n### Слушаю\n\n[[Album source|Текущий альбом]]\n\nОписание."; }
  private static String editorialNowBody() { return "## Кратко\n\nКратко.\n\n## Eyebrow\n\nСейчас.\n\n## Обновлено\n\nСегодня.\n\n## Читаю\n\n### Материал\n\nТекст.\n\nДействие вопроса:: Вопрос\nДействие записи:: Запись"; }
}
