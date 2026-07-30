package dev.eugene.astroexport.review;

import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.references.PageReferenceMap;

public record ApprovedPageSnapshot(
    String collection,
    String publicId,
    String pageRef,
    String sourcePath,
    ManifestEntry russian,
    ManifestEntry english,
    PageReferenceMap references,
    SnapshotHashes hashes) { }
