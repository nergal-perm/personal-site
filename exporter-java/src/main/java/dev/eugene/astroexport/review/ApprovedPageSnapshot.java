package dev.eugene.astroexport.review;

import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.references.PageReferenceMap;
import java.nio.file.Path;

public record ApprovedPageSnapshot(
    String collection,
    String publicId,
    String pageRef,
    String sourcePath,
    ManifestEntry russian,
    ManifestEntry english,
    PageReferenceMap references,
    SnapshotHashes hashes,
    InputFiles inputFiles) {

  public ApprovedPageSnapshot(
      String collection,
      String publicId,
      String pageRef,
      String sourcePath,
      ManifestEntry russian,
      ManifestEntry english,
      PageReferenceMap references,
      SnapshotHashes hashes) {
    this(
        collection,
        publicId,
        pageRef,
        sourcePath,
        russian,
        english,
        references,
        hashes,
        InputFiles.none());
  }

  public ApprovedPageSnapshot {
    inputFiles = inputFiles == null ? InputFiles.none() : inputFiles;
  }

  public ApprovedPageSnapshot withInputFiles(InputFiles inputFiles) {
    return new ApprovedPageSnapshot(
        collection,
        publicId,
        pageRef,
        sourcePath,
        russian,
        english,
        references,
        hashes,
        inputFiles);
  }

  public record InputFiles(
      InputFile approvedRussian,
      InputFile approvedEnglish,
      InputFile approvedReferences,
      InputFile catalog) {
    public static InputFiles none() {
      return new InputFiles(null, null, null, null);
    }
  }

  public record InputFile(Path path, byte[] bytes) {
    public InputFile {
      bytes = bytes == null ? null : bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes == null ? null : bytes.clone();
    }
  }
}
