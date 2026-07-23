package dev.eugene.astroexport.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.SelectionResult;
import java.nio.file.Path;
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

  private static dev.eugene.astroexport.model.ManifestEntry only(dev.eugene.astroexport.model.ManifestResult result) { return result.entries().getFirst(); }
  private static dev.eugene.astroexport.model.ManifestEntry byId(dev.eugene.astroexport.model.ManifestResult result, String id) { return result.entries().stream().filter(entry -> id.equals(entry.metadata().get("id"))).findFirst().orElseThrow(); }
  private static SelectionResult selection(Note... notes) { return new SelectionResult(List.of(notes), List.of(), notes.length, notes.length); }
  private static Note note(String path, String title, String id, String collection, String type, Map<String, Object> extra, String body) {
    Map<String, Object> metadata = new LinkedHashMap<>(); metadata.put("title", title); metadata.put("publish", true); metadata.put("publicId", id); metadata.put("publicCollection", collection); metadata.put("publicContentType", type); metadata.putAll(extra);
    return new Note(Path.of(path), path, title, metadata, body, true, id, collection, type, List.of());
  }
}
