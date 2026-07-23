package dev.eugene.astroexport.review;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.translation.TranslationPatch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;

/** Durable RU/EN Markdown review workspace operations. */
public final class ReviewWorkspace {
  private static final Set<String> CONTROL_FIELDS = Set.of(
      "sourceHash", "translationStatus", "translatedAt", "translationProfile");
  private static final Pattern TRAILING_LINE_WHITESPACE = Pattern.compile("[ \\t]+(?=\\R|$)");
  private static final Pattern ALIASED_CONTROL_KEY = Pattern.compile(
      "(?m)^\\*[A-Za-z0-9_-]+[ \\t]*:");
  private static final int EXCHANGE_FLAG = 0x00000002;
  private static final int AT_FDCWD = -100;
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
    boolean hasReferences = payload.containsKey("referenceTranslations");
    Object referenceValue = payload.remove("referenceTranslations");
    Map<String, Object> references = hasReferences
        ? stringMap(referenceValue, "referenceTranslations")
        : Map.of();
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(payload);
    CONTROL_FIELDS.forEach(metadata::remove);
    if (target.editorial()
        && "home".equals(target.publicId())
        && entry.translationSourceMetadata() != null) {
      Object authoredCurrent =
          entry.translationSourceMetadata().getOrDefault("current", List.of());
      if (authoredCurrent instanceof List<?> sourceCurrent) {
        metadata.put(
            "current",
            parseCurrent(entry, target.publicId(), parsed.body(), sourceCurrent.size()));
      }
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
    ParsedMarkdown parsed = parseMarkdown(content, "English review");
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(parsed.metadata());
    metadata.put("translationStatus", "generated");
    return serializeMarkdown(metadata, parsed.body());
  }

  public static String setReviewedStatusPreservingContent(String content) {
    Frontmatter frontmatter = frontmatter(content);
    String header = content.substring(frontmatter.headerStart(), frontmatter.close());
    if (ALIASED_CONTROL_KEY.matcher(header).find()) {
      throw new IllegalArgumentException(
          "English review must use an explicit translationStatus key and value");
    }
    Node root;
    try {
      root = new Compose(yamlSettings("English review")).composeString(header).orElse(null);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(
          "invalid English review frontmatter: " + error.getMessage(), error);
    }
    if (!(root instanceof MappingNode mapping)) {
      throw new IllegalArgumentException("English review frontmatter must be a mapping");
    }
    List<NodeTuple> matches = mapping.getValue().stream()
        .filter(tuple -> tuple.getKeyNode() instanceof ScalarNode key
            && "translationStatus".equals(key.getValue()))
        .toList();
    if (matches.size() != 1) {
      throw new IllegalArgumentException(
          "English review must contain exactly one translationStatus field");
    }
    NodeTuple match = matches.getFirst();
    if (!(match.getValueNode() instanceof ScalarNode value)) {
      throw new IllegalArgumentException("translationStatus must be a scalar");
    }
    int keyEnd = markIndex(header, match.getKeyNode().getEndMark());
    int valueStart = markIndex(header, value.getStartMark());
    int valueEnd = markIndex(header, value.getEndMark());
    if (valueStart < keyEnd) {
      throw new IllegalArgumentException(
          "English review must use an explicit translationStatus key and value");
    }
    if (valueStart >= valueEnd) {
      throw new IllegalArgumentException(
          "translationStatus must have an explicit scalar value");
    }
    String rewrittenHeader =
        header.substring(0, valueStart) + "\"reviewed\"" + header.substring(valueEnd);
    String reviewed = content.substring(0, frontmatter.headerStart())
        + rewrittenHeader
        + content.substring(frontmatter.close());
    ParsedMarkdown parsed = parseMarkdown(reviewed, "reviewed English review");
    if (!"reviewed".equals(parsed.metadata().get("translationStatus"))) {
      throw new IllegalArgumentException("could not set translationStatus to reviewed");
    }
    return reviewed;
  }

  public static Path replaceEnglishReviewFile(
      Path reviewRoot,
      String content,
      String collection,
      String publicId,
      byte[] expectedContent) {
    Path target = reviewRoot.resolve(collection).resolve(publicId).resolve("en.md");
    replaceAtomically(target, content, expectedContent);
    return target;
  }

  public static Path replaceEnglishReviewFile(
      Path reviewRoot,
      String content,
      String collection,
      String publicId) {
    return replaceEnglishReviewFile(reviewRoot, content, collection, publicId, null);
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

  private static Frontmatter frontmatter(String content) {
    if (!content.startsWith("---\n") && !content.startsWith("---\r\n")) {
      throw new IllegalArgumentException("English review must contain YAML frontmatter");
    }
    int headerStart = content.indexOf('\n') + 1;
    int close = findDelimiter(content, headerStart);
    if (close < 0) {
      throw new IllegalArgumentException("English review must contain closed YAML frontmatter");
    }
    return new Frontmatter(headerStart, close);
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
      return parseMarkdown(Files.readString(path, StandardCharsets.UTF_8), path.toString());
    } catch (IOException | RuntimeException error) {
      throw new IllegalArgumentException("invalid review translation " + path + ": " + error.getMessage(), error);
    }
  }

  private static ParsedMarkdown parseMarkdown(String markdown, String label) {
    Frontmatter frontmatter = frontmatter(markdown);
    String header = markdown.substring(frontmatter.headerStart(), frontmatter.close());
    int bodyStart = markdown.indexOf('\n', frontmatter.close());
    String body = bodyStart < 0 ? "" : markdown.substring(bodyStart + 1);
    try {
      Object loaded = new Load(yamlSettings(label)).loadFromString(header);
      return new ParsedMarkdown(stringMap(loaded, "frontmatter"), body);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(
          "invalid " + label + " frontmatter: " + error.getMessage(), error);
    }
  }

  private static LoadSettings yamlSettings(String label) {
    return LoadSettings.builder()
        .setLabel(label)
        .setAllowDuplicateKeys(false)
        .build();
  }

  private static int markIndex(String source, Optional<Mark> mark) {
    if (mark.isEmpty()) {
      throw new IllegalArgumentException("translationStatus YAML location is unavailable");
    }
    int codePointIndex = mark.get().getIndex();
    if (codePointIndex < 0 || codePointIndex > source.codePointCount(0, source.length())) {
      throw new IllegalArgumentException("translationStatus YAML location is invalid");
    }
    return source.offsetByCodePoints(0, codePointIndex);
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
    replaceAtomically(target, content, null);
  }

  private static void replaceAtomically(
      Path target,
      String content,
      byte[] expectedContent) {
    validateExistingLeaf(target);
    Path temporary = null;
    boolean retainTemporary = false;
    try {
      Files.createDirectories(target.getParent());
      validateExpectedContent(target, expectedContent);
      temporary = Files.createTempFile(
          target.getParent(), "." + target.getFileName() + ".", ".tmp");
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      if (expectedContent == null) {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } else {
        guardedExchange(target, temporary, content.getBytes(StandardCharsets.UTF_8), expectedContent);
      }
    } catch (ConcurrentReviewUpdateException error) {
      retainTemporary = temporary != null && temporary.equals(error.preservedPath());
      throw error;
    } catch (IOException error) {
      throw new IllegalStateException("cannot replace review file " + target, error);
    } finally {
      if (temporary != null && !retainTemporary) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // The replacement result is already determined; stale temporary cleanup is best effort.
        }
      }
    }
  }

  private static void guardedExchange(
      Path target,
      Path temporary,
      byte[] payload,
      byte[] expectedContent) throws IOException {
    validateExpectedContent(target, expectedContent);
    exchangePaths(target, temporary);
    byte[] displaced;
    try {
      displaced = Files.readAllBytes(temporary);
    } catch (IOException error) {
      Path preserved = preserveTemporary(temporary, target);
      throw new ConcurrentReviewUpdateException(
          "displaced review file could not be verified; preserved at " + preserved, true, preserved);
    }
    if (!Arrays.equals(displaced, expectedContent)) {
      Path preserved = rollbackExchange(target, temporary, payload);
      throw new ConcurrentReviewUpdateException(
          "guarded review file changed at the atomic commit boundary",
          false,
          preserved);
    }
    boolean targetIsPayload = Arrays.equals(Files.readAllBytes(target), payload);
    boolean displacedIsExpected = Arrays.equals(Files.readAllBytes(temporary), expectedContent);
    if (targetIsPayload && displacedIsExpected) {
      return;
    }
    if (targetIsPayload) {
      Path preserved = rollbackExchange(target, temporary, payload);
      throw new ConcurrentReviewUpdateException(
          "displaced review file changed before final commit verification",
          false,
          preserved);
    }
    Path preserved = preserveTemporary(temporary, target);
    throw new ConcurrentReviewUpdateException(
        "review file changed immediately after atomic exchange; displaced bytes preserved at "
            + preserved,
        true,
        preserved);
  }

  private static Path rollbackExchange(Path target, Path temporary, byte[] payload)
      throws IOException {
    try {
      exchangePaths(target, temporary);
    } catch (IOException rollbackError) {
      Path preserved = preserveTemporary(temporary, target);
      throw new ConcurrentReviewUpdateException(
          "guarded review update conflicted and atomic rollback failed; preserved at " + preserved,
          true,
          preserved);
    }
    if (Arrays.equals(Files.readAllBytes(temporary), payload)) {
      return null;
    }
    return preserveTemporary(temporary, target);
  }

  private static Path preserveTemporary(Path temporary, Path target) {
    try {
      Path directory = Files.createTempDirectory(
          target.getParent(), "." + target.getFileName() + ".astro-export-conflict-");
      Path preserved = directory.resolve(target.getFileName());
      Files.move(temporary, preserved);
      return preserved;
    } catch (IOException error) {
      throw new ConcurrentReviewUpdateException(
          "conflicting review bytes remain in temporary file " + temporary,
          true,
          temporary,
          error);
    }
  }

  private static void exchangePaths(Path first, Path second) throws IOException {
    if (first.getFileSystem() != FileSystems.getDefault()
        || second.getFileSystem() != FileSystems.getDefault()) {
      throw new IOException("atomic path exchange requires the default local filesystem");
    }
    String firstPath = first.toAbsolutePath().toString();
    String secondPath = second.toAbsolutePath().toString();
    int result;
    try {
      NativeLibrary libc = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME);
      if (Platform.isMac()) {
        Function exchange = libc.getFunction("renamex_np");
        result = exchange.invokeInt(new Object[] {
            firstPath, secondPath, EXCHANGE_FLAG
        });
      } else if (Platform.isLinux()) {
        Function exchange = libc.getFunction("renameat2");
        result = exchange.invokeInt(new Object[] {
            AT_FDCWD, firstPath, AT_FDCWD, secondPath, EXCHANGE_FLAG
        });
      } else {
        throw new IOException(
            "atomic path exchange is unsupported on " + System.getProperty("os.name"));
      }
    } catch (UnsatisfiedLinkError error) {
      throw new IOException("atomic path exchange is unavailable", error);
    }
    if (result != 0) {
      int errorNumber = Native.getLastError();
      throw new IOException("atomic path exchange failed with errno " + errorNumber);
    }
  }

  private static void validateExpectedContent(Path target, byte[] expectedContent)
      throws IOException {
    if (expectedContent == null) {
      return;
    }
    if (!Files.exists(target) || !Arrays.equals(expectedContent, Files.readAllBytes(target))) {
      throw new ConcurrentReviewUpdateException("guarded review file changed: " + target);
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

  private record Frontmatter(int headerStart, int close) { }

  private static final class ReviewIoException extends RuntimeException {
    ReviewIoException(Throwable cause) {
      super(cause);
    }
  }

  public static final class ConcurrentReviewUpdateException extends IllegalStateException {
    private final boolean committed;
    private final Path preservedPath;

    ConcurrentReviewUpdateException(String message) {
      this(message, false, null);
    }

    ConcurrentReviewUpdateException(String message, boolean committed, Path preservedPath) {
      super(message);
      this.committed = committed;
      this.preservedPath = preservedPath;
    }

    ConcurrentReviewUpdateException(
        String message,
        boolean committed,
        Path preservedPath,
        Throwable cause) {
      super(message, cause);
      this.committed = committed;
      this.preservedPath = preservedPath;
    }

    public boolean committed() {
      return committed;
    }

    public Path preservedPath() {
      return preservedPath;
    }
  }
}
