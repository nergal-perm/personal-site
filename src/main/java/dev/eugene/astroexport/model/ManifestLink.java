package dev.eugene.astroexport.model;

/** Provenance for a retained or removed public reference. */
public record ManifestLink(String sourcePath, String target, String kind, String publicId, String route,
                           boolean retained) {
  public ManifestLink(String sourcePath, String target, String kind, String publicId, String route) {
    this(sourcePath, target, kind, publicId, route, true);
  }

  public ManifestLink(String sourcePath, String target, String kind) {
    this(sourcePath, target, kind, null, null, false);
  }
}
