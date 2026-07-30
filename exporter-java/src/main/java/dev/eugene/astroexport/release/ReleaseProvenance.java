package dev.eugene.astroexport.release;

import dev.eugene.astroexport.fs.TreeHasher;
import java.util.List;

/** Canonical manifest proving a staged Astro tree came from an approved release. */
public record ReleaseProvenance(
    int schemaVersion,
    List<SelectedPage> selectedPages,
    List<TreeHasher.ManagedTreeHash> managedTrees,
    List<TreeHasher.ManagedFileHash> managedFiles,
    int activationCount,
    int deactivationCount,
    String payloadDigest) {
  public static final int SCHEMA_VERSION = 1;

  public ReleaseProvenance {
    selectedPages = List.copyOf(selectedPages);
    managedTrees = List.copyOf(managedTrees);
    managedFiles = List.copyOf(managedFiles);
  }

  public record SelectedPage(
      String pageRef,
      String publicId,
      String sourcePath,
      String ruSha256,
      String enSha256,
      String referencesSha256,
      String ruProjectionSha256,
      String enProjectionSha256) { }
}
