package dev.eugene.astroexport.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record Note(
    Path path,
    String vaultPath,
    String title,
    Map<String, Object> frontmatter,
    String body,
    boolean publish,
    String publicId,
    String publicCollection,
    String publicContentType,
    List<String> aliases) {
}
