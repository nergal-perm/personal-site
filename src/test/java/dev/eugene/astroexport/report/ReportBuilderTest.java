package dev.eugene.astroexport.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.assets.ResolvedAsset;
import dev.eugene.astroexport.fs.SiteWriter;
import dev.eugene.astroexport.fs.TreeHasher;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestLink;
import dev.eugene.astroexport.model.ManifestResult;
import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.model.TranslationUse;
import dev.eugene.astroexport.translation.TranslationValidator;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReportBuilderTest {
  @Test
  void queueReportRanksByDistinctReferrers() {
    String report = ReportBuilder.buildPublishQueueReport(
        List.of("A", "B"),
        Map.of(
            "A", List.of("Целевая заметка", "Целевая заметка", "Редкая"),
            "B", List.of("Целевая заметка")),
        List.of("cover.png"));

    String queueSection = report.split("## Publish queue")[1];
    List<String> lines = queueSection.lines().filter(line -> line.startsWith("-")).toList();
    assertEquals(List.of("- Целевая заметка — 2", "- Редкая — 1"), lines);
    assertTrue(report.contains("## Published (2)"));
    assertTrue(report.contains("- cover.png"));
  }

  @Test
  void selectionReportListsIncludedAndExcludedNotes() {
    Note included = new Note(
        Path.of("/vault/blog/Essay.md"),
        "blog/Essay.md",
        "Essay",
        Map.of(
            "id", "internal-id",
            "publicId", "essay",
            "publicCollection", "blog",
            "publicContentType", "essay"),
        "",
        true,
        "essay",
        "blog",
        "essay",
        List.of());
    SelectionResult result = new SelectionResult(
        List.of(included),
        List.of(new SelectionResult.Exclusion(Path.of("/vault/blog/Draft.md"), "missing publicCollection", "blog/Draft.md")),
        3,
        2);

    String report = ReportBuilder.buildSelectionReport(result);

    assertTrue(report.contains("# Astro export dry-run"));
    assertTrue(report.contains("Files matched by `rg`: 3"));
    assertTrue(report.contains("Confirmed `publish: true` frontmatter: 2"));
    assertTrue(report.contains("## Included (1)"));
    assertTrue(report.contains("`blog/Essay.md` → `blog/essay` (`essay`)"));
    assertTrue(report.contains("## Excluded (1)"));
    assertTrue(report.contains("`blog/Draft.md` — missing publicCollection"));
  }

  @Test
  void manifestReportListsTargetsAndLinkDecisions() {
    SelectionResult selection = new SelectionResult(
        List.of(),
        List.of(new SelectionResult.Exclusion(Path.of("/vault/blog/Draft.md"), "missing publicContentType", "blog/Draft.md")),
        2,
        2);
    ManifestResult manifest = new ManifestResult(
        List.of(
            new ManifestEntry("blog/editorial/now.md", "src/data/pages/ru/now.json", "/ru/now/", Map.of("id", "now"), ""),
            new ManifestEntry("blog/Essay.md", "src/content/blog/ru/essay.md", "/ru/essays/essay/", Map.of("id", "essay"), "")),
        List.of(
            new ManifestEntry("blog/editorial/now.md", "src/data/pages/en/now.json", "/en/now/", Map.of("id", "now", "translationStatus", "generated"), ""),
            new ManifestEntry("blog/Essay.md", "src/content/blog/en/essay.md", "/en/essays/essay/", Map.of("id", "essay", "translationStatus", "generated"), "")),
        List.of(new ManifestLink(
            "blog/editorial/now.md",
            "The Lean Startup",
            "body",
            "book-the-lean-startup",
            "/ru/library/book-the-lean-startup/")),
        List.of(new ManifestLink("blog/editorial/now.md", "Private Note", "body")),
        List.of("cover.png"),
        List.of(),
        List.of(
            new TranslationUse("now", "review", "review/editorial/now/en.md"),
            new TranslationUse("essay", "review", "review/blog/essay/en.md")));

    String report = ReportBuilder.buildManifestReport(selection, manifest);

    assertTrue(report.contains("Normalized RU records: 2"));
    assertTrue(report.contains("Generated EN records: 2"));
    assertTrue(report.contains("Review translations: 2"));
    assertFalse(report.contains("Translation sync"));
    assertFalse(report.contains("Reviewed-to-generated downgrades"));
    assertFalse(report.contains("RU cache"));
    assertTrue(report.contains("Translation blockers: 0"));
    assertTrue(report.contains("Retained public links: 1"));
    assertTrue(report.contains("Stripped non-public links: 1"));
    assertTrue(report.contains("`blog/editorial/now.md` → `src/data/pages/ru/now.json` → `/ru/now/`"));
    assertTrue(report.contains("`blog/editorial/now.md` → `src/data/pages/en/now.json` → `/en/now/`"));
    assertTrue(report.contains("`The Lean Startup` → `/ru/library/book-the-lean-startup/` (`body`)"));
    assertTrue(report.contains("`Private Note` (`body`)"));
    assertTrue(report.contains("`cover.png`"));
    assertTrue(report.contains("`blog/Draft.md` — missing publicContentType"));
  }

  @Test
  void translationBlockerHasSeparateReportSectionWithSourceAndPublicId() {
    SelectionResult selection = new SelectionResult(
        List.of(new Note(
            Path.of("/vault/blog/Essay.md"),
            "blog/Essay.md",
            "Essay",
            Map.of(
                "publicId", "essay",
                "publicCollection", "blog",
                "publicContentType", "essay"),
            "",
            true,
            "essay",
            "blog",
            "essay",
            List.of())),
        List.of(),
        1,
        1);
    TranslationValidator.TranslationValidationException error =
        new TranslationValidator.TranslationValidationException(
            "blog/Essay.md", "essay", "missing translation fixture");

    String report = ReportBuilder.buildBlockedManifestReport(selection, error);

    assertTrue(report.contains("Manifest blocked: 0"));
    assertTrue(report.contains("Translation blockers: 1"));
    assertTrue(report.contains("## Translation blockers (1)"));
    assertTrue(report.contains("`blog/Essay.md` — `essay` — missing translation fixture"));
    assertFalse(report.contains("## Blocking errors"));
  }

  @Test
  void writeReportListsRecordCountSortedHashesAndAssetProvenance() {
    SelectionResult selection = new SelectionResult(List.of(), List.of(), 1, 1);
    ManifestResult manifest = new ManifestResult(
        List.of(new ManifestEntry("blog/Essay.md", "src/content/blog/ru/essay.md", "/ru/essays/essay/", Map.of("id", "essay"), "")),
        List.of(new ManifestEntry("blog/Essay.md", "src/content/blog/en/essay.md", "/en/essays/essay/", Map.of("id", "essay"), "")),
        List.of(),
        List.of(),
        List.of("media/cover.png"),
        List.of());
    Path source = Path.of("/vault/media/cover.png");
    SiteWriter.WriteResult result = new SiteWriter.WriteResult(
        4,
        List.of(new ResolvedAsset(
            "media/cover.png",
            source,
            "abc123.png",
            "/assets/vault/abc123.png",
            "abc123")),
        List.of(
            new TreeHasher.ManagedTreeHash("src/data/pages", "pages-hash"),
            new TreeHasher.ManagedTreeHash("public/assets/vault", "assets-hash"),
            new TreeHasher.ManagedTreeHash("src/content", "content-hash")));

    String report = ReportBuilder.buildWriteReport(selection, manifest, result);

    assertTrue(report.startsWith("# Astro export write\n"));
    assertTrue(report.contains("Generated records: 4"));
    List<String> hashLines = report.lines()
        .filter(line -> line.startsWith("- `") && line.contains("sha256:"))
        .map(line -> line.split("`")[1])
        .toList();
    assertEquals(List.of("public/assets/vault", "src/content", "src/data/pages"), hashLines);
    assertTrue(report.contains(
        "Vault reference `media/cover.png` → source `/vault/media/cover.png` "
            + "→ `/assets/vault/abc123.png` (`abc123`)"));
    assertTrue(report.contains("## Selected sources"));
    assertTrue(report.contains("## Normalized manifest"));
  }

  @Test
  void blockedWriteReportKeepsAvailableSelectionAndManifestContext() {
    SelectionResult selection = new SelectionResult(List.of(), List.of(), 3, 2);
    ManifestResult manifest = new ManifestResult(
        List.of(new ManifestEntry("", "", "", Map.of("id", "one"), "")),
        List.of(new ManifestEntry("", "", "", Map.of("id", "one"), "")),
        List.of(),
        List.of(),
        List.of(),
        List.of());

    String report = ReportBuilder.buildBlockedWriteReport(
        new RuntimeException("Astro content gate failed with exit code 7"),
        selection,
        manifest);

    assertTrue(report.startsWith("# Astro export write blocked\n"));
    assertTrue(report.contains("Files matched by `rg`: 3"));
    assertTrue(report.contains("Included by selector: 0"));
    assertTrue(report.contains("Manifest records before staging: 2"));
    assertFalse(report.contains("Generated records before validation"));
    assertTrue(report.contains("```text\nAstro content gate failed with exit code 7\n```"));
    assertFalse(report.contains("RuntimeException"));
  }

  @Test
  void committedErrorReportIncludesTruthfulStateResultAndRecoveryPaths() {
    SelectionResult selection = new SelectionResult(List.of(), List.of(), 2, 1);
    ManifestResult manifest = new ManifestResult(
        List.of(new ManifestEntry("", "", "", Map.of("id", "one"), "")),
        List.of(new ManifestEntry("", "", "", Map.of("id", "one"), "")),
        List.of(),
        List.of(),
        List.of(),
        List.of());
    SiteWriter.WriteResult result = new SiteWriter.WriteResult(
        4,
        List.of(),
        List.of(new TreeHasher.ManagedTreeHash("src/content", "content-hash")));
    SiteWriter.WriterException error = new SiteWriter.WriterException(
        "cleanup failed after commit",
        true,
        List.of("/tmp/recovery-one", "/tmp/recovery-two"));

    String report = ReportBuilder.buildCommittedWriteErrorReport(error, selection, manifest, result);

    assertTrue(report.startsWith("# Astro export committed with errors\n"));
    assertTrue(report.contains("Status: committed-with-errors"));
    assertTrue(report.contains("Generated records committed: 4"));
    assertTrue(report.contains("cleanup failed after commit"));
    assertTrue(report.contains("`/tmp/recovery-one`"));
    assertTrue(report.contains("`/tmp/recovery-two`"));
    assertFalse(report.toLowerCase().contains("blocked"));
    assertFalse(report.toLowerCase().contains("preserved"));
  }
}
