package dev.eugene.astroexport.model;

import dev.eugene.astroexport.assets.ResolvedAsset;
import java.util.List;

public record ManifestResult(
    List<ManifestEntry> entries,
    List<ManifestEntry> englishEntries,
    List<ManifestLink> retainedLinks,
    List<ManifestLink> strippedLinks,
    List<String> assets,
    List<ResolvedAsset> resolvedAssets,
    List<TranslationUse> translationUses) {
  public ManifestResult(
      List<ManifestEntry> entries,
      List<ManifestLink> retainedLinks,
      List<ManifestLink> strippedLinks,
      List<String> assets) {
    this(entries, List.of(), retainedLinks, strippedLinks, assets, List.of(), List.of());
  }

  public ManifestResult(
      List<ManifestEntry> entries,
      List<ManifestEntry> englishEntries,
      List<ManifestLink> retainedLinks,
      List<ManifestLink> strippedLinks,
      List<String> assets,
      List<ResolvedAsset> resolvedAssets) {
    this(entries, englishEntries, retainedLinks, strippedLinks, assets, resolvedAssets, List.of());
  }

  public ManifestResult {
    entries = List.copyOf(entries);
    englishEntries = List.copyOf(englishEntries);
    retainedLinks = List.copyOf(retainedLinks);
    strippedLinks = List.copyOf(strippedLinks);
    assets = List.copyOf(assets);
    resolvedAssets = List.copyOf(resolvedAssets);
    translationUses = List.copyOf(translationUses);
  }
}
