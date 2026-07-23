package dev.eugene.astroexport.review;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.translation.TranslationPatch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

/** Durable RU/EN Markdown review workspace operations. */
public final class ReviewWorkspace {
  private static final Set<String> CONTROL_FIELDS = Set.of(
      "sourceHash", "translationStatus", "translatedAt", "translationProfile");
  private static final Pattern TRAILING_LINE_WHITESPACE = Pattern.compile("[ \\t]+(?=\\R|$)");
  private static final Pattern ALIASED_CONTROL_KEY = Pattern.compile(
      "(?m)^\\*[A-Za-z0-9_-]+[ \\t]*:");
  private static final Pattern TRANSLATION_STATUS_LINE = Pattern.compile(
      "(?m)^(translationStatus[ \\t]*:[ \\t]*)([^#\\r\\n]*?)([ \\t]*(?:#[^\\r\\n]*)?)(\\r?)$");
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
  private static final Dump YAML_DUMP = new Dump(DumpSettings.builder()
      .setDefaultFlowStyle(FlowStyle.BLOCK)
      .build());

  private ReviewWorkspace() { }

  public static Path writeRuReviewFile(Path reviewRoot, ManifestEntry entry) {
    Target target = target(entry);
    Path path = reviewRoot.resolve(target.collection()).resolve(target.publicId()).resolve("ru.md");
    String markdown = target.editorial()
        ? serializeEditorial(entry)
        : serializeContent(entry);
    replaceAtomically(path, clean(markdown));
    return path;
  }

  public static TranslationPatch loadEnglishPatch(Path reviewRoot, ManifestEntry entry) {
    Target target = target(entry);
    Path path = reviewRoot.resolve(target.collection()).resolve(target.publicId()).resolve("en.md");
    if (!Files.exists(path)) {
      throw new IllegalArgumentException(
          entry.sourcePath() + ": " + target.publicId() + ": missing translation; checked " + path);
    }
    ParsedMarkdown parsed = parseMarkdown(path);
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>(parsed.metadata());
    Map<String, Object> references = map(payload.remove("referenceTranslations"), "referenceTranslations");
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(payload);
    CONTROL_FIELDS.forEach(metadata::remove);
    if (target.editorial()
        && "home".equals(target.publicId())
        && entry.metadata().get("current") instanceof List<?> sourceCurrent) {
      metadata.put("current", parseCurrent(entry, target.publicId(), parsed.body(), sourceCurrent.size()));
    }
    return normalizePatch(
        entry,
        target.publicId(),
        payload,
        metadata,
        references,
        target.editorial() ? "" : parsed.body());
  }

  public static List<Path> migrateOverrides(Path overridesRoot, Path reviewRoot) {
    List<Path> written = new ArrayList<>();
    if (Files.exists(overridesRoot)) {
      try (var paths = Files.walk(overridesRoot, 2)) {
        paths.filter(Files::isRegularFile)
            .filter(path -> path.getFileName().toString().endsWith(".md"))
            .sorted()
            .forEach(path -> {
              String collection = path.getParent().getFileName().toString();
              String publicId = removeExtension(path.getFileName().toString());
              Path target = reviewRoot.resolve(collection).resolve(publicId).resolve("en.md");
              try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, Files.readString(path), StandardCharsets.UTF_8);
                written.add(target);
              } catch (IOException error) {
                throw new ReviewIoException(error);
              }
            });
      } catch (IOException error) {
        throw new IllegalStateException("cannot scan override Markdown", error);
      } catch (ReviewIoException error) {
        throw new IllegalStateException("cannot migrate override Markdown", error.getCause());
      }
    }

    Path editorialRoot = overridesRoot.resolve("editorial");
    if (Files.isDirectory(editorialRoot)) {
      try (var paths = Files.list(editorialRoot)) {
        paths.filter(path -> path.getFileName().toString().endsWith(".json"))
            .sorted()
            .forEach(path -> written.add(migrateEditorialJson(path, reviewRoot)));
      } catch (IOException error) {
        throw new IllegalStateException("cannot scan editorial overrides", error);
      }
    }
    return List.copyOf(written);
  }

  public static String setGeneratedReviewStatus(String content) {
    return rewriteTranslationStatus(content, "generated");
  }

  public static String setReviewedStatusPreservingContent(String content) {
    return rewriteTranslationStatus(content, "reviewed");
  }

  static void replaceAtomicallyForTest(Path target, String content, Path occupiedTarget)
      throws IOException {
    validateExistingLeaf(target);
    Files.createDirectories(target.getParent());
    Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
    try {
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      Files.move(temporary, occupiedTarget);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static Path migrateEditorialJson(Path source, Path reviewRoot) {
    try {
      Map<String, Object> payload = JSON.readValue(
          Files.readString(source), new TypeReference<LinkedHashMap<String, Object>>() { });
      Map<String, Object> translated = map(payload.get("metadata"), "metadata");
      LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
      for (String control : CONTROL_FIELDS) {
        if (payload.containsKey(control)) {
          metadata.put(control, payload.get(control));
        }
      }
      metadata.putAll(translated);
      Object current = metadata.remove("current");
      if (payload.containsKey("referenceTranslations")) {
        metadata.put("referenceTranslations", payload.get("referenceTranslations"));
      }
      String body = payload.get("body") instanceof String value ? value : "";
      String currentBody = serializeCurrent(current);
      if (!currentBody.isEmpty()) {
        body = body.isEmpty() ? currentBody : currentBody + "\n\n" + body;
      }
      String publicId = removeExtension(source.getFileName().toString());
      Path target = reviewRoot.resolve("editorial").resolve(publicId).resolve("en.md");
      Files.createDirectories(target.getParent());
      Files.writeString(target, serializeMarkdown(metadata, body), StandardCharsets.UTF_8);
      return target;
    } catch (IOException error) {
      throw new IllegalStateException("cannot migrate editorial override " + source, error);
    }
  }

  private static String rewriteTranslationStatus(String content, String status) {
    if (!content.startsWith("---\n") && !content.startsWith("---\r\n")) {
      throw new IllegalArgumentException("English review must contain YAML frontmatter");
    }
    int headerStart = content.indexOf('\n') + 1;
    int close = findDelimiter(content, headerStart);
    if (close < 0) {
      throw new IllegalArgumentException("English review must contain closed YAML frontmatter");
    }
    String header = content.substring(headerStart, close);
    if (ALIASED_CONTROL_KEY.matcher(header).find()) {
      throw new IllegalArgumentException(
          "English review must use an explicit translationStatus key and value");
    }
    Matcher matcher = TRANSLATION_STATUS_LINE.matcher(header);
    if (!matcher.find()) {
      throw new IllegalArgumentException(
          "English review must contain exactly one translationStatus field");
    }
    int valueStart = matcher.start(2);
    int valueEnd = matcher.end(2);
    String value = matcher.group(2).strip();
    if (value.isEmpty() || value.startsWith("*") || matcher.find()) {
      throw new IllegalArgumentException(
          "English review must use an explicit translationStatus key and value");
    }
    String rewrittenHeader = header.substring(0, valueStart)
        + "\"" + status + "\""
        + header.substring(valueEnd);
    return content.substring(0, headerStart) + rewrittenHeader + content.substring(close);
  }

  private static TranslationPatch normalizePatch(
      ManifestEntry entry,
      String publicId,
      Map<String, Object> controls,
      Map<String, Object> metadata,
      Map<String, Object> references,
      String body) {
    String sourceHash = requiredString(entry, publicId, controls, "sourceHash", false);
    String status = requiredString(entry, publicId, controls, "translationStatus", false);
    if (!Set.of("generated", "reviewed").contains(status)) {
      fail(entry, publicId, "translationStatus must be generated or reviewed");
    }
    String translatedAt = requiredString(entry, publicId, controls, "translatedAt", true);
    try {
      LocalDate.parse(translatedAt);
    } catch (DateTimeParseException error) {
      fail(entry, publicId, "translatedAt must be a real ISO date");
    }
    String profile = requiredString(entry, publicId, controls, "translationProfile", false);
    return new TranslationPatch(
        sourceHash, status, translatedAt, profile, metadata, references, body.strip(), "review");
  }

  private static String requiredString(
      ManifestEntry entry,
      String publicId,
      Map<String, Object> values,
      String field,
      boolean allowDate) {
    Object value = values.get(field);
    String text;
    if (value instanceof String string) {
      text = string.strip();
    } else if (allowDate && value instanceof LocalDate date) {
      text = date.toString();
    } else {
      fail(entry, publicId, field + " must be a non-empty string");
      throw new AssertionError("unreachable");
    }
    if (text.isEmpty()) {
      fail(entry, publicId, field + " must be a non-empty string");
    }
    return text;
  }

  private static ParsedMarkdown parseMarkdown(Path path) {
    try {
      String markdown = Files.readString(path, StandardCharsets.UTF_8);
      if (!markdown.startsWith("---\n") && !markdown.startsWith("---\r\n")) {
        throw new IllegalArgumentException("English review must contain YAML frontmatter");
      }
      int firstBreak = markdown.indexOf('\n') + 1;
      int close = findDelimiter(markdown, firstBreak);
      if (close < 0) {
        throw new IllegalArgumentException("English review must contain closed YAML frontmatter");
      }
      String header = markdown.substring(firstBreak, close);
      int bodyStart = markdown.indexOf('\n', close);
      String body = bodyStart < 0 ? "" : markdown.substring(bodyStart + 1);
      Object loaded = new Load(LoadSettings.builder()
          .setLabel(path.toString())
          .setAllowDuplicateKeys(false)
          .build()).loadFromString(header);
      return new ParsedMarkdown(stringMap(loaded, "frontmatter"), body);
    } catch (IOException | RuntimeException error) {
      throw new IllegalArgumentException("invalid review translation " + path + ": " + error.getMessage(), error);
    }
  }

  private static int findDelimiter(String markdown, int start) {
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
        return -1;
      }
      lineStart = lineEnd + 1;
    }
    return -1;
  }

  private static String serializeContent(ManifestEntry entry) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(entry.metadata());
    metadata.put("route", entry.route());
    metadata.put("targetPath", entry.targetPath());
    return serializeMarkdown(metadata, entry.body());
  }

  private static String serializeEditorial(ManifestEntry entry) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(entry.metadata());
    Object current = metadata.remove("current");
    String currentBody = containsCurrent(entry.body()) ? "" : serializeCurrent(current);
    String body = currentBody;
    if (!entry.body().isEmpty()) {
      body = body.isEmpty() ? entry.body() : body + "\n\n" + entry.body();
    }
    return serializeMarkdown(metadata, body);
  }

  private static String serializeCurrent(Object value) {
    if (!(value instanceof List<?> items)) {
      return "";
    }
    List<String> cards = new ArrayList<>();
    for (Object item : items) {
      if (!(item instanceof Map<?, ?> card)) {
        continue;
      }
      String label = text(card.get("label"));
      if (label.isEmpty()) {
        continue;
      }
      List<String> sections = new ArrayList<>();
      sections.add("### " + label);
      String title = reviewText(card.get("title"));
      String description = reviewText(card.get("text"));
      if (!title.isEmpty()) {
        sections.add(title);
      }
      if (!description.isEmpty()) {
        sections.add(description);
      }
      cards.add(String.join("\n\n", sections));
    }
    return cards.isEmpty() ? "" : "## Сейчас\n\n" + String.join("\n\n", cards);
  }

  private static List<Map<String, Object>> parseCurrent(
      ManifestEntry entry,
      String publicId,
      String body,
      int expectedCards) {
    List<String> lines = body.lines().toList();
    int start = -1;
    for (int index = 0; index < lines.size(); index++) {
      if ("## Сейчас".equals(lines.get(index).strip())) {
        if (start >= 0) {
          fail(entry, publicId, "editorial translation must contain exactly one ## Сейчас section");
        }
        start = index;
      }
    }
    if (start < 0) {
      fail(entry, publicId, "editorial translation must contain exactly one ## Сейчас section");
    }
    if (lines.subList(0, start).stream().anyMatch(line -> !line.isBlank())) {
      fail(entry, publicId, "unexpected content before ## Сейчас section");
    }
    List<Map<String, Object>> cards = new ArrayList<>();
    int index = start + 1;
    while (index < lines.size()) {
      while (index < lines.size() && lines.get(index).isBlank()) {
        index++;
      }
      if (index >= lines.size()) {
        break;
      }
      String heading = lines.get(index).strip();
      if (!heading.startsWith("### ")) {
        fail(entry, publicId, "unexpected section content: " + heading);
      }
      if (cards.size() >= expectedCards) {
        fail(entry, publicId, "editorial translation has more current cards than the Russian source");
      }
      String label = heading.substring(4).strip();
      index++;
      while (index < lines.size() && lines.get(index).isBlank()) {
        index++;
      }
      if (index >= lines.size() || lines.get(index).strip().startsWith("### ")) {
        fail(entry, publicId, "current card " + (cards.size() + 1) + " is missing a title");
      }
      String title = lines.get(index).strip();
      if (title.startsWith("#")) {
        fail(entry, publicId, "unexpected heading: " + title);
      }
      index++;
      List<String> description = new ArrayList<>();
      while (index < lines.size()) {
        String line = lines.get(index).strip();
        if (line.startsWith("### ")) {
          break;
        }
        if (line.startsWith("#")) {
          fail(entry, publicId, "unexpected heading: " + line);
        }
        if (!line.isEmpty()) {
          description.add(line);
        }
        index++;
      }
      if (description.isEmpty()) {
        fail(entry, publicId, "current card " + (cards.size() + 1) + " is missing a description");
      }
      cards.add(Map.of(
          "label", label,
          "title", title,
          "text", String.join(" ", description)));
    }
    if (cards.size() != expectedCards) {
      fail(
          entry,
          publicId,
          "editorial translation must contain " + expectedCards
              + " current cards, found " + cards.size());
    }
    return List.copyOf(cards);
  }

  private static String serializeMarkdown(Map<String, Object> metadata, String body) {
    String yaml = YAML_DUMP.dumpToString(metadata);
    String content = body == null ? "" : body.strip();
    return "---\n" + yaml + "---\n" + (content.isEmpty() ? "" : content + "\n");
  }

  private static String clean(String markdown) {
    return TRAILING_LINE_WHITESPACE.matcher(markdown).replaceAll("");
  }

  private static void replaceAtomically(Path target, String content) {
    validateExistingLeaf(target);
    try {
      Files.createDirectories(target.getParent());
      Path temporary = Files.createTempFile(
          target.getParent(), "." + target.getFileName() + ".", ".tmp");
      try {
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException error) {
      throw new IllegalStateException("cannot replace review file " + target, error);
    }
  }

  private static void validateExistingLeaf(Path target) {
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (Files.isSymbolicLink(target)) {
      throw new IllegalArgumentException("Existing " + target.getFileName() + " must not be a symbolic link.");
    }
    try {
      BasicFileAttributes attributes = Files.readAttributes(
          target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile()) {
        throw new IllegalArgumentException(
            "Existing " + target.getFileName() + " must be a regular file.");
      }
      Object links = Files.getAttribute(target, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
      if (links instanceof Number count && count.longValue() != 1) {
        throw new IllegalArgumentException(
            "Existing " + target.getFileName() + " must not have multiple hard links.");
      }
    } catch (UnsupportedOperationException ignored) {
      // The supported Unix/macOS targets expose nlink. Other file systems still get type checks.
    } catch (IOException error) {
      throw new IllegalStateException("cannot validate existing review file " + target, error);
    }
  }

  private static Target target(ManifestEntry entry) {
    Object id = entry.metadata().get("id");
    if (!(id instanceof String publicId) || publicId.isBlank()) {
      throw new IllegalArgumentException(entry.sourcePath() + ": RU id must be a non-empty string");
    }
    String[] parts = entry.targetPath().split("/");
    if (parts.length == 5
        && "src".equals(parts[0])
        && "content".equals(parts[1])
        && "ru".equals(parts[3])) {
      return new Target(publicId.strip(), parts[2], false);
    }
    if (parts.length == 5
        && "src".equals(parts[0])
        && "data".equals(parts[1])
        && "pages".equals(parts[2])
        && "ru".equals(parts[3])) {
      return new Target(publicId.strip(), "editorial", true);
    }
    throw new IllegalArgumentException("unsupported RU target path " + entry.targetPath());
  }

  private static Map<String, Object> map(Object value, String field) {
    if (value == null) {
      return Map.of();
    }
    return stringMap(value, field);
  }

  private static Map<String, Object> stringMap(Object value, String field) {
    if (!(value instanceof Map<?, ?> values)) {
      throw new IllegalArgumentException(field + " must be an object");
    }
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException(field + " keys must be strings");
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static String reviewText(Object value) {
    if (value instanceof String string) {
      return string.strip();
    }
    if (!(value instanceof List<?> tokens)) {
      return "";
    }
    List<String> text = new ArrayList<>();
    for (Object token : tokens) {
      if (token instanceof Map<?, ?> map
          && "text".equals(map.get("kind"))
          && map.get("value") instanceof String string
          && !string.isBlank()) {
        text.add(string.strip());
      }
    }
    return String.join(" ", text);
  }

  private static String text(Object value) {
    return value instanceof String string ? string.strip() : "";
  }

  private static boolean containsCurrent(String body) {
    return body.lines().anyMatch(line -> "## Сейчас".equals(line.strip()));
  }

  private static String removeExtension(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? filename : filename.substring(0, dot);
  }

  private static void fail(ManifestEntry entry, String publicId, String reason) {
    throw new IllegalArgumentException(entry.sourcePath() + ": " + publicId + ": " + reason);
  }

  private record Target(String publicId, String collection, boolean editorial) { }

  private record ParsedMarkdown(Map<String, Object> metadata, String body) { }

  private static final class ReviewIoException extends RuntimeException {
    ReviewIoException(Throwable cause) {
      super(cause);
    }
  }
}
