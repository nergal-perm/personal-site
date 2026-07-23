package dev.eugene.astroexport.assets;

import java.nio.file.Path;

/** A vault asset with its content-addressed public destination. */
public record ResolvedAsset(
    String reference,
    Path sourcePath,
    String outputName,
    String publicUrl,
    String sha256) { }
