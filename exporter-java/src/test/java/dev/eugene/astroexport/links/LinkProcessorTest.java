package dev.eugene.astroexport.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eugene.astroexport.model.Note;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LinkProcessorTest {
  private final LinkProcessor processor = new LinkProcessor();

  @Test
  void retainsPublishedWikilinksAsPublicRoutes() {
    ManifestLink result = processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "See [[Public Note|article]]."), List.of(note("Public Note", "blog/Public Note.md", "public-note", "blog", "note", "")));

    assertEquals("See [article](/ru/notes/public-note/).", result.body());
    assertEquals(List.of(new LinkProcessor.ResolvedLink("Public Note", "public-note", "/ru/notes/public-note/")), result.retained());
  }

  @Test
  void stripsPrivateLinksWithoutLeakingPathOrTimestamp() {
    ManifestLink result = processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "See [[private/201412260402 Secret Note]] and [[private/hidden|label]]."), List.of());

    assertEquals("See Secret Note and label.", result.body());
    assertEquals(List.of("private/201412260402 Secret Note", "private/hidden"), result.stripped());
  }

  @Test
  void blocksUnpublishedTransclusionsAndCollectsAssets() {
    Note source = note("Source", "blog/Source.md", "source", "blog", "note", "![[cover.png]]");
    assertEquals(List.of("cover.png"), processor.processLinks(source, List.of()).assets());
    assertThrows(LinkProcessor.TransclusionException.class, () -> processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "![[Private Note]]"), List.of()));
  }

  @Test
  void leavesLinksAndAssetsInsideProtectedContextsUntouched() {
    String body = "```md\n![[fenced.png]] and [[Public]]\n```\n`![[inline.png]]` <!-- [[Public]] --> \\![[escaped.png]] ![[rendered.png]] [[Public]].";
    ManifestLink result = processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", body), List.of(note("Public", "blog/Public.md", "public", "blog", "note", "")));

    assertEquals("```md\n![[fenced.png]] and [[Public]]\n```\n`![[inline.png]]` <!-- [[Public]] --> \\![[escaped.png]] ![[rendered.png]] [Public](/ru/notes/public/).", result.body());
    assertEquals(List.of("rendered.png"), result.assets());
  }

  @Test
  void removesObsidianCommentsBeforeProcessingAndLeavesMarkersInCode() {
    ManifestLink result = processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "Visible.\n%% [[Secret]] %%\n```md\n%% literal %%\n```"), List.of());
    assertEquals("Visible.\n\n```md\n%% literal %%\n```", result.body());
  }

  @Test
  void removesUnclosedObsidianCommentsToTheEndOfTheBody() {
    assertEquals("Visible.\n", processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "Visible.\n%% private forever"), List.of()).body());
  }

  @Test
  void leavesPreBlocksAndLongerClosingFencesOutsideLinkProcessing() {
    ManifestLink pre = processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "<pre>\n![[inside.png]] and [[Private]].\n</pre>\n![[rendered.png]]"), List.of());
    assertEquals("<pre>\n![[inside.png]] and [[Private]].\n</pre>\n![[rendered.png]]", pre.body());
    assertEquals(List.of("rendered.png"), pre.assets());

    ManifestLink fence = processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "```md\n[[Code]]\n  ````\nRendered [[Private]]."), List.of());
    assertEquals("```md\n[[Code]]\n  ````\nRendered Private.", fence.body());
    assertEquals(List.of("Private"), fence.stripped());
  }

  @Test
  void honoursFencesWithLoneCarriageReturnLineEndings() {
    ManifestLink result = processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "~~~md\r[[Code]]\r~~~\rRendered [[Private]]."), List.of());
    assertEquals("~~~md\r[[Code]]\r~~~\rRendered Private.", result.body());
    assertEquals(List.of("Private"), result.stripped());
  }

  @Test
  void preservesObsidianCommentMarkersInsidePreBlocks() {
    String body = "<pre>\n%% literal comment markers %%\n![[inside.png]] and [[Private]].\n</pre>\nVisible.";
    ManifestLink result = processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", body), List.of());
    assertEquals(body, result.body());
    assertEquals(List.of(), result.assets());
    assertEquals(List.of(), result.stripped());
  }

  @Test
  void stripsHeadingLinksToPrivateNotesAndNormalizesHeadingFragmentsForPublicRoutes() {
    assertEquals("private section", processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "[[Private#Section|private section]]"), List.of()).body());
    assertEquals("[public](/ru/notes/public/#section-title)", processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "[[Public#Section Title|public]]"), List.of(note("Public", "blog/Public.md", "public", "blog", "note", ""))).body());
  }

  @Test
  void tokenizesEditorialTextAsReferencesAndVisiblePrivateLabels() {
    ManifestLink result = processor.tokenizeEditorialText("Read [[Public]] and [[private/note|the private note]].", List.of(note("Public", "blog/Public.md", "public", "blog", "note", "")));

    assertEquals(List.of(
        Map.of("kind", "text", "value", "Read "),
        Map.of("kind", "reference", "target", "public"),
        Map.of("kind", "text", "value", " and the private note.")), result.body());
    assertEquals(List.of("private/note"), result.stripped());
  }

  @Test
  void tokenizationHonoursProtectedContexts() {
    ManifestLink result = processor.tokenizeEditorialText("`[[Public]]` %% [[Public]] %% and [[Public]].", List.of(note("Public", "blog/Public.md", "public", "blog", "note", "")));
    assertEquals(List.of(Map.of("kind", "text", "value", "`[[Public]]`  and "), Map.of("kind", "reference", "target", "public"), Map.of("kind", "text", "value", ".")), result.body());
  }

  @Test
  void resolvesWithSpecifiedIndexPrecedenceAndRoutes() {
    Note path = note("Different", "notes/Shared.md", "path", "blog", "essay", "");
    Note id = note("Shared", "notes/Other.md", "shared", "music", "album", "");
    Note stem = note("202301010000 Shared", "notes/202301010000 Shared.md", "stem", "concepts", "concept", "");
    Note title = note("Internal", "notes/Title.md", "title", "bibliography", "book", "", Map.of("title", "Shared"));
    Note alias = note("Alias note", "notes/Alias.md", "alias", "editorial", "curated_page", "", Map.of(), List.of("Shared"));

    assertEquals("[Shared](/ru/essays/path/)", processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "[[notes/Shared]]"), List.of(path, id, stem, title, alias)).body());
    assertEquals("[shared](/ru/music/shared/)", processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "[[shared]]"), List.of(path, id, stem, title, alias)).body());
    assertEquals("[Shared](/ru/concepts/stem/)", processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "[[Shared]]"), List.of(stem, title, alias)).body());
  }

  @Test
  void resolvesFrontmatterTitleBeforeAnotherNotesAlias() {
    Collection<Note> notes = List.of(note("One", "notes/One.md", "one", "blog", "note", "", Map.of("title", "Shared")), note("Two", "notes/Two.md", "two", "blog", "note", "", Map.of(), List.of("Shared")));
    assertEquals("[Shared](/ru/notes/one/)", processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "[[Shared]]"), notes).body());
  }

  @Test
  void resolvesAnUnambiguousAliasToThePublicRouteAndRetainedMetadata() {
    Note aliased = note("Internal title", "notes/Aliased.md", "aliased", "music", "album", "", Map.of(), List.of("Public alias"));
    ManifestLink result = processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "Listen to [[Public alias|this album]]."), List.of(aliased));

    assertEquals("Listen to [this album](/ru/music/aliased/).", result.body());
    assertEquals(List.of(new LinkProcessor.ResolvedLink("Public alias", "aliased", "/ru/music/aliased/")), result.retained());
  }

  @Test
  void rejectsDuplicateFrontmatterTitles() {
    Collection<Note> notes = List.of(note("One", "notes/One.md", "one", "blog", "note", "", Map.of("title", "Duplicate")), note("Two", "notes/Two.md", "two", "blog", "note", "", Map.of("title", "Duplicate")));
    assertThrows(LinkProcessor.ManifestValidationException.class, () -> processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "[[Duplicate]]"), notes));
  }

  @Test
  void rejectsDuplicateAliases() {
    Collection<Note> notes = List.of(note("One", "notes/One.md", "one", "blog", "note", "", Map.of(), List.of("Duplicate")), note("Two", "notes/Two.md", "two", "blog", "note", "", Map.of(), List.of("Duplicate")));
    assertThrows(LinkProcessor.ManifestValidationException.class, () -> processor.processLinks(note("Source", "blog/Source.md", "source", "blog", "note", "[[Duplicate]]"), notes));
  }

  private static Note note(String title, String vaultPath, String publicId, String collection, String type, String body) {
    return note(title, vaultPath, publicId, collection, type, body, Map.of(), List.of());
  }

  private static Note note(String title, String vaultPath, String publicId, String collection, String type, String body, Map<String, Object> frontmatter) {
    return note(title, vaultPath, publicId, collection, type, body, frontmatter, List.of());
  }

  private static Note note(String title, String vaultPath, String publicId, String collection, String type, String body, Map<String, Object> frontmatter, List<String> aliases) {
    return new Note(Path.of(vaultPath), vaultPath, title, frontmatter, body, true, publicId, collection, type, aliases);
  }
}
