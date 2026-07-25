package dev.eugene.astroexport.links;

import java.util.List;

public record ManifestLink(Object body, List<LinkProcessor.ResolvedLink> retained, List<String> stripped,
                           List<String> assets) {
  public ManifestLink {
    retained = List.copyOf(retained);
    stripped = List.copyOf(stripped);
    assets = List.copyOf(assets);
  }
}
