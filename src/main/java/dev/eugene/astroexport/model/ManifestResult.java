package dev.eugene.astroexport.model;

import java.util.List;

public record ManifestResult(List<ManifestEntry> entries, List<ManifestLink> retainedLinks,
                             List<ManifestLink> strippedLinks, List<String> assets) {
  public ManifestResult {
    entries = List.copyOf(entries);
    retainedLinks = List.copyOf(retainedLinks);
    strippedLinks = List.copyOf(strippedLinks);
    assets = List.copyOf(assets);
  }
}
