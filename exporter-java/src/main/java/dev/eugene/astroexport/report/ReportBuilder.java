package dev.eugene.astroexport.report;

import dev.eugene.astroexport.assets.ResolvedAsset;
import dev.eugene.astroexport.fs.SiteWriter;
import dev.eugene.astroexport.fs.TreeHasher;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestLink;
import dev.eugene.astroexport.model.ManifestResult;
import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.translation.TranslationValidator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds stable operator reports for dry-run, manifest, and write steps. */
public final class ReportBuilder {
  private ReportBuilder() { }

  public static String buildSelectionReport(SelectionResult result) {
    ArrayList<String> lines = new ArrayList<>(List.of(
        "# Astro export dry-run",
        "",
        "## Summary",
        "",
        "- Files matched by `rg`: " + result.matched(),
        "- Confirmed `publish: true` frontmatter: " + result.confirmed(),
        "- Included: " + result.included().size(),
        "- Excluded after declaring `publish`: " + result.excluded().size(),
        "",
        "## Included (" + result.included().size() + ")",
        ""));
    for (Note note : result.included()) {
      lines.add("- `" + note.vaultPath() + "` → `" + note.publicCollection() + "/" + note.publicId()
          + "` (`" + note.publicContentType() + "`)");
    }
    lines.add("");
    lines.add("## Excluded (" + result.excluded().size() + ")");
    lines.add("");
    for (SelectionResult.Exclusion item : result.excluded()) {
      lines.add("- `" + item.vaultPath() + "` — " + item.reason());
    }
    return String.join("\n", lines) + "\n";
  }

  public static String buildManifestReport(SelectionResult selection, ManifestResult manifest) {
    long reviewTranslations = manifest.translationUses().stream()
        .filter(use -> "review".equals(use.origin()))
        .count();
    ArrayList<String> lines = new ArrayList<>(List.of(
        "# Astro export dry-run",
        "",
        "## Summary",
        "",
        "- Files matched by `rg`: " + selection.matched(),
        "- Confirmed `publish: true` frontmatter: " + selection.confirmed(),
        "- Included by selector: " + selection.included().size(),
        "- Excluded by selector: " + selection.excluded().size(),
        "- Normalized RU records: " + manifest.entries().size(),
        "- Generated EN records: " + manifest.englishEntries().size(),
        "- Review translations: " + reviewTranslations,
        "- Translation blockers: 0",
        "- Retained public links: " + manifest.retainedLinks().size(),
        "- Stripped non-public links: " + manifest.strippedLinks().size(),
        "- Collected assets: " + manifest.assets().size(),
        "- Manifest blocked: 0",
        "",
        "## Selected sources (" + selection.included().size() + ")",
        ""));
    for (Note note : selection.included()) {
      lines.add("- `" + note.vaultPath() + "` → `" + note.publicCollection() + "/" + note.publicId()
          + "` (`" + note.publicContentType() + "`)");
    }
    lines.add("");
    lines.add("## Normalized manifest (" + manifest.entries().size() + ")");
    lines.add("");
    addEntries(lines, manifest.entries());
    lines.add("");
    lines.add("## Generated English manifest (" + manifest.englishEntries().size() + ")");
    lines.add("");
    addEntries(lines, manifest.englishEntries());
    lines.add("");
    lines.add("## Retained public links (" + manifest.retainedLinks().size() + ")");
    lines.add("");
    for (ManifestLink link : manifest.retainedLinks()) {
      lines.add("- `" + link.sourcePath() + "` — `" + link.target() + "` → `" + link.route()
          + "` (`" + link.kind() + "`)");
    }
    lines.add("");
    lines.add("## Stripped non-public links (" + manifest.strippedLinks().size() + ")");
    lines.add("");
    for (ManifestLink link : manifest.strippedLinks()) {
      lines.add("- `" + link.sourcePath() + "` — `" + link.target() + "` (`" + link.kind() + "`)");
    }
    lines.add("");
    lines.add("## Assets (" + manifest.assets().size() + ")");
    lines.add("");
    for (String asset : manifest.assets()) {
      lines.add("- `" + asset + "`");
    }
    lines.add("");
    lines.add("## Selector exclusions (" + selection.excluded().size() + ")");
    lines.add("");
    for (SelectionResult.Exclusion item : selection.excluded()) {
      lines.add("- `" + item.vaultPath() + "` — " + item.reason());
    }
    return String.join("\n", lines) + "\n";
  }

  public static String buildBlockedManifestReport(
      SelectionResult selection,
      TranslationValidator.TranslationValidationException error) {
    return String.join("\n", List.of(
        "# Astro export dry-run",
        "",
        "## Summary",
        "",
        "- Files matched by `rg`: " + selection.matched(),
        "- Confirmed `publish: true` frontmatter: " + selection.confirmed(),
        "- Included by selector: " + selection.included().size(),
        "- Excluded by selector: " + selection.excluded().size(),
        "- Normalized RU records: " + selection.included().size(),
        "- Generated EN records: 0",
        "- Manifest blocked: 0",
        "- Translation blockers: 1",
        "",
        "## Translation blockers (1)",
        "",
        "- `" + error.sourcePath() + "` — `" + error.publicId() + "` — " + error.reason(),
        ""));
  }

  public static String buildWriteReport(
      SelectionResult selection,
      ManifestResult manifest,
      SiteWriter.WriteResult result) {
    List<String> manifestLines = buildManifestReport(selection, manifest).lines().toList();
    int contextStart = 0;
    for (int index = 0; index < manifestLines.size(); index++) {
      if (manifestLines.get(index).startsWith("## Selected sources")) {
        contextStart = index;
        break;
      }
    }
    ArrayList<String> lines = new ArrayList<>(List.of(
        "# Astro export write",
        "",
        "## Write result",
        "",
        "- Generated records: " + result.writtenEntries(),
        "- Managed trees: " + result.managedTreeHashes().size(),
        "- Resolved assets: " + result.resolvedAssets().size(),
        "",
        "## Managed tree hashes",
        ""));
    result.managedTreeHashes().stream()
        .sorted(Comparator.comparing(TreeHasher.ManagedTreeHash::relative))
        .forEach(hash -> lines.add("- `" + hash.relative() + "` — sha256: `" + hash.sha256() + "`"));
    lines.add("");
    lines.add("## Resolved asset mappings");
    lines.add("");
    result.resolvedAssets().stream()
        .sorted(Comparator.comparing(ResolvedAsset::reference))
        .forEach(asset -> lines.add("- Vault reference `" + asset.reference() + "` → source `" + asset.sourcePath()
            + "` → `" + asset.publicUrl() + "` (`" + asset.sha256() + "`)"));
    lines.add("");
    lines.add("## Export summary");
    lines.add("");
    lines.addAll(manifestLines.subList(4, contextStart));
    lines.addAll(manifestLines.subList(contextStart, manifestLines.size()));
    return String.join("\n", lines).stripTrailing() + "\n";
  }

  public static String buildBlockedWriteReport(
      Exception error,
      SelectionResult selection,
      ManifestResult manifest) {
    ArrayList<String> lines = new ArrayList<>(List.of(
        "# Astro export write blocked",
        "",
        "## Summary",
        "",
        "- Status: blocked"));
    if (selection != null) {
      lines.add("- Files matched by `rg`: " + selection.matched());
      lines.add("- Confirmed `publish: true` frontmatter: " + selection.confirmed());
      lines.add("- Included by selector: " + selection.included().size());
      lines.add("- Excluded by selector: " + selection.excluded().size());
    }
    if (manifest != null) {
      lines.add("- Manifest records before staging: " + (manifest.entries().size() + manifest.englishEntries().size()));
      lines.add("- Collected assets: " + manifest.assets().size());
    }
    String errorText = error.getMessage() == null ? "" : error.getMessage();
    lines.addAll(List.of("", "## Error", "", "```text", errorText, "```", ""));
    return String.join("\n", lines);
  }

  public static String buildCommittedWriteErrorReport(
      SiteWriter.WriterException error,
      SelectionResult selection,
      ManifestResult manifest,
      SiteWriter.WriteResult result) {
    ArrayList<String> lines = new ArrayList<>(List.of(
        "# Astro export committed with errors",
        "",
        "## Summary",
        "",
        "- Status: committed-with-errors",
        "- Managed output state: committed before the later error"));
    if (selection != null) {
      lines.add("- Files matched by `rg`: " + selection.matched());
      lines.add("- Confirmed `publish: true` frontmatter: " + selection.confirmed());
      lines.add("- Included by selector: " + selection.included().size());
      lines.add("- Excluded by selector: " + selection.excluded().size());
    }
    if (manifest != null) {
      lines.add("- Manifest records prepared: " + (manifest.entries().size() + manifest.englishEntries().size()));
      lines.add("- Collected assets: " + manifest.assets().size());
    }
    if (result != null) {
      lines.add("- Generated records committed: " + result.writtenEntries());
      lines.add("- Managed trees committed: " + result.managedTreeHashes().size());
      lines.add("- Resolved assets committed: " + result.resolvedAssets().size());
    }
    lines.addAll(List.of("", "## Error after commit", "", "```text", error.detail(), "```"));
    if (!error.recoveryPaths().isEmpty()) {
      lines.add("");
      lines.add("## Recovery paths");
      lines.add("");
      for (String path : error.recoveryPaths()) {
        lines.add("- `" + path + "`");
      }
    }
    lines.add("");
    return String.join("\n", lines);
  }

  public static String buildPublishQueueReport(
      List<String> publishedTitles,
      Map<String, List<String>> strippedByNote,
      List<String> assets) {
    LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
    strippedByNote.values().forEach(targets -> targets.stream()
        .distinct()
        .forEach(target -> counts.merge(target, 1, Integer::sum)));
    ArrayList<String> lines = new ArrayList<>(List.of(
        "# Export report",
        "",
        "## Published (" + publishedTitles.size() + ")",
        ""));
    publishedTitles.forEach(title -> lines.add("- " + title));
    lines.add("");
    lines.add("## Assets (" + assets.size() + ")");
    lines.add("");
    assets.stream().sorted().forEach(asset -> lines.add("- " + asset));
    lines.add("");
    lines.add("## Publish queue");
    lines.add("");
    lines.add("Unpublished link targets by number of referring public notes:");
    lines.add("");
    counts.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
            .thenComparing(Map.Entry.comparingByKey()))
        .forEach(entry -> lines.add("- " + entry.getKey() + " — " + entry.getValue()));
    return String.join("\n", lines) + "\n";
  }

  private static void addEntries(List<String> lines, List<ManifestEntry> entries) {
    for (ManifestEntry entry : entries) {
      lines.add("- `" + entry.sourcePath() + "` → `" + entry.targetPath() + "` → `" + entry.route() + "`");
    }
  }
}
