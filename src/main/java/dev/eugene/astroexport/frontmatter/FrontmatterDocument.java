package dev.eugene.astroexport.frontmatter;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

public record FrontmatterDocument(
    Path path,
    String vaultPath,
    Map<String, Object> metadata,
    String body) {
  public static FrontmatterDocument parse(Path path, String vaultPath, String markdown) {
    if (!markdown.startsWith("---\n") && !markdown.startsWith("---\r\n")) {
      return new FrontmatterDocument(path, vaultPath, Map.of(), markdown);
    }

    int metadataStart = markdown.indexOf('\n') + 1;
    int closingDelimiterStart = findClosingDelimiter(markdown, metadataStart);
    if (closingDelimiterStart < 0) {
      return new FrontmatterDocument(path, vaultPath, Map.of(), markdown);
    }

    int closingDelimiterEnd = markdown.indexOf('\n', closingDelimiterStart);
    String metadataSource = markdown.substring(metadataStart, closingDelimiterStart);
    String body = closingDelimiterEnd < 0 ? "" : markdown.substring(closingDelimiterEnd + 1).strip();
    return new FrontmatterDocument(path, vaultPath, parseMetadata(path, metadataSource), body);
  }

  private static int findClosingDelimiter(String markdown, int start) {
    int lineStart = start;
    while (lineStart < markdown.length()) {
      int lineEnd = markdown.indexOf('\n', lineStart);
      int contentEnd = lineEnd < 0 ? markdown.length() : lineEnd;
      if (contentEnd > lineStart && markdown.charAt(contentEnd - 1) == '\r') {
        contentEnd--;
      }
      if (markdown.substring(lineStart, contentEnd).equals("---")) {
        return lineStart;
      }
      if (lineEnd < 0) {
        break;
      }
      lineStart = lineEnd + 1;
    }
    return -1;
  }

  private static Map<String, Object> parseMetadata(Path path, String source) {
    Object loaded = new Load(LoadSettings.builder().setLabel(path.toString()).build()).loadFromString(source);
    if (loaded == null) {
      return Map.of();
    }
    if (!(loaded instanceof Map<?, ?> values)) {
      throw new IllegalArgumentException("frontmatter must be a YAML mapping");
    }

    Map<String, Object> metadata = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("frontmatter keys must be strings");
      }
      metadata.put(key, entry.getValue());
    }
    return Collections.unmodifiableMap(metadata);
  }
}
