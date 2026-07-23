package dev.eugene.astroexport.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.editorial.EditorialParser;
import dev.eugene.astroexport.links.LinkProcessor;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestLink;
import dev.eugene.astroexport.model.ManifestResult;
import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.validation.PublicationDiagnostic;
import dev.eugene.astroexport.validation.PublicationValidator;
import dev.eugene.astroexport.markdown.MarkdownScanner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds deterministic, Russian source manifests from publication-selected notes. */
public final class ManifestBuilder {
  private static final Set<String> TOPICS = Set.of("systems", "software", "ai-work", "thinking", "reading", "music", "personal-systems");
  private static final Set<String> WORKFLOW_FIELDS = Set.of("publicWorkflowStatus", "publicTranslationStatus", "publicWorkflowUpdated", "publicWorkflowDiagnostic");
  private static final Set<String> STRUCTURAL_EDITORIAL_FIELDS = Set.of("id", "type", "date", "status", "topics", "links", "pinned", "target", "route", "number", "sourceHash", "language", "sourceLanguage", "translationStatus", "key", "layout");
  private static final Pattern LEADING_H1 = Pattern.compile("\\A(?:[ \\t]*\\R)* {0,3}#[ \\t]+([^\\r\\n]+)(?:\\R|\\z)");
  private static final Pattern ATX_CLOSER = Pattern.compile("[ \\t]+(?<!\\\\)#+[ \\t]*$");
  private static final Pattern SCALAR_LINK = Pattern.compile("^\\[\\[([^\\]|#]+)(?:#[^\\]|]*)?(?:\\|([^\\]]+))?\\]\\]$");
  private static final Pattern SCALAR_EMBED = Pattern.compile("^!\\[\\[([^\\]|#]+)(?:#[^\\]|]*)?(?:\\|([^\\]]+))?\\]\\]$");
  private static final Pattern BOOK_DESCRIPTION = Pattern.compile("(?is)<div\\s+class=[\"']book-description[\"'][^>]*>.*?<p>(.*?)</p>");
  private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
  private static final ObjectMapper HASH_JSON = new ObjectMapper();
  private final PublicationValidator publicationValidator = new PublicationValidator();
  private final LinkProcessor linkProcessor = new LinkProcessor();
  private final EditorialParser editorialParser = new EditorialParser();

  public ManifestResult buildRussianManifest(SelectionResult selection) {
    List<Note> notes = selection.included().stream().map(this::sanitize).sorted(Comparator.comparing(Note::vaultPath)).toList();
    List<ManifestEntry> entries = new ArrayList<>();
    for (Note note : notes) entries.add(normalize(note));
    Map<String, ManifestEntry> entriesByPath = new LinkedHashMap<>();
    for (ManifestEntry entry : entries) entriesByPath.put(entry.sourcePath(), entry);
    List<ManifestLink> retained = new ArrayList<>(); List<ManifestLink> stripped = new ArrayList<>(); Set<String> assets = new LinkedHashSet<>();
    for (Note note : notes) {
      ManifestEntry entry = entriesByPath.get(note.vaultPath());
      LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(entry.metadata());
      String body = entry.body();
      if (note.publicCollection().equals("editorial")) {
        resolveEditorialMetadata(entry.sourcePath(), metadata, notes, retained, stripped);
        resolvePins(entry.sourcePath(), metadata, notes);
        filterEditorialReferences(entry.sourcePath(), metadata, notes, stripped);
      } else {
        try {
          dev.eugene.astroexport.links.ManifestLink links = linkProcessor.processLinks(copy(note, body), notes);
          body = (String) links.body(); assets.addAll(links.assets());
          for (LinkProcessor.ResolvedLink link : links.retained()) retained.add(new ManifestLink(entry.sourcePath(), link.target(), "body", link.publicId(), link.route()));
          for (String target : links.stripped()) stripped.add(new ManifestLink(entry.sourcePath(), target, "body"));
        } catch (LinkProcessor.TransclusionException error) { throw new ManifestTransclusionException(entry.sourcePath(), error.getMessage());
        } catch (LinkProcessor.ManifestValidationException error) { throw new ManifestValidationException(entry.sourcePath(), linkTarget(error.getMessage()), "is ambiguous; use a publicId or vault path"); }
      }
      resolveFrontmatterLinks(entry.sourcePath(), metadata, notes, retained, stripped);
      if (note.publicCollection().equals("blog") && "claim".equals(metadata.get("contentType"))) resolveClaimLinks(entry.sourcePath(), metadata, notes, retained, stripped);
      metadata.put("sourceHash", sourceHash(metadata, body));
      entriesByPath.put(entry.sourcePath(), new ManifestEntry(entry.sourcePath(), entry.targetPath(), entry.route(), metadata, body));
    }
    return new ManifestResult(notes.stream().map(note -> entriesByPath.get(note.vaultPath())).toList(), retained, stripped, assets.stream().sorted().toList());
  }

  private ManifestEntry normalize(Note note) {
    validatePublication(note);
    LinkedHashMap<String, Object> metadata;
    if (note.publicCollection().equals("editorial")) {
      metadata = editorialCommon(note);
      try {
        metadata = new LinkedHashMap<>(editorialParser.normalize(
            note.vaultPath(), note.publicId().strip(), note.frontmatter(), note.body(), metadata));
      } catch (EditorialParser.ManifestValidationException error) {
        throw new ManifestValidationException(error.sourcePath(), error.fieldName(), error.reason());
      }
      metadata.put("language", "ru");
      metadata.put("sourceLanguage", "ru");
      metadata.put("translationStatus", "source");
    } else {
      metadata = common(note);
      if (note.publicCollection().equals("blog")) { metadata.put("contentType", note.publicContentType()); if (note.publicContentType().equals("claim")) claimMetadata(note, metadata); }
      if (note.publicCollection().equals("music")) musicMetadata(note, metadata);
      if (note.publicCollection().equals("bibliography")) bookMetadata(note, metadata);
    }
    String body = publicBody(note);
    validateEntry(note, metadata);
    return new ManifestEntry(note.vaultPath(), targetPath(note), route(note), metadata, body);
  }

  private Note sanitize(Note note) {
    Map<String, Object> frontmatter = new LinkedHashMap<>(note.frontmatter()); WORKFLOW_FIELDS.forEach(frontmatter::remove);
    return new Note(note.path(), note.vaultPath(), note.title(), frontmatter, MarkdownScanner.stripObsidianComments(note.body()), note.publish(), note.publicId(), note.publicCollection(), note.publicContentType(), note.aliases());
  }
  private static Note copy(Note note, String body) { return new Note(note.path(), note.vaultPath(), note.title(), note.frontmatter(), body, note.publish(), note.publicId(), note.publicCollection(), note.publicContentType(), note.aliases()); }
  private void validatePublication(Note note) { List<PublicationDiagnostic> diagnostics = publicationValidator.validate(note); if (!diagnostics.isEmpty()) { PublicationDiagnostic diagnostic = diagnostics.getFirst(); throw new ManifestValidationException(note.vaultPath(), diagnostic.field(), diagnostic.message()); } }

  private static LinkedHashMap<String, Object> common(Note note) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(); Map<String, Object> fm = note.frontmatter();
    metadata.put("id", note.publicId().strip()); metadata.put("title", title(note)); metadata.put("publish", true); metadata.put("description", description(note));
    metadata.put("topics", stringList(note, "topics")); metadata.put("tags", stringList(note, "tags")); metadata.put("aliases", stringList(note, "aliases")); metadata.put("links", stringList(note, "links"));
    metadata.put("language", "ru"); metadata.put("sourceLanguage", "ru"); metadata.put("translationStatus", "source");
    String date = normalizeDate(fm.get("date")); if (date == null) date = dateFromId(fm.get("id")); if (date != null) metadata.put("date", date);
    String updated = normalizeDate(fm.get("updated")); if (updated != null) metadata.put("updated", updated);
    for (String key : List.of("cover", "status", "foundational", "readTime")) if (fm.containsKey(key) && fm.get(key) != null) metadata.put(key, fm.get(key));
    return metadata;
  }
  private static LinkedHashMap<String, Object> editorialCommon(Note note) {
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", note.publicId().strip());
    metadata.put("topics", stringList(note, "topics"));
    metadata.put("links", stringList(note, "links"));
    metadata.put("title", title(note));
    return metadata;
  }
  private static String title(Note note) { Object value = note.frontmatter().get("title"); return value == null || value.toString().strip().isEmpty() ? note.title().strip() : value.toString().strip(); }
  private static String description(Note note) { Object explicit = note.frontmatter().get("description"); if (explicit != null && !explicit.toString().strip().isEmpty()) return explicit.toString().strip(); if (note.publicCollection().equals("editorial")) return MarkdownScanner.section(note.body(), "Кратко").orElse(""); if (note.publicCollection().equals("music")) return MarkdownScanner.section(note.body(), "Контекст записи").orElse(""); if (note.publicCollection().equals("bibliography")) return bookDescription(note.body()); if (note.publicCollection().equals("blog") && note.publicContentType().equals("claim")) return string(note.frontmatter().get("statement")); return ""; }
  private static List<String> stringList(Note note, String field) { Object value = note.frontmatter().get(field); if (value == null) return List.of(); List<?> values = value instanceof List<?> list ? list : List.of(value); if (values.stream().anyMatch(item -> !(item instanceof String))) throw new ManifestValidationException(note.vaultPath(), field, "must be a string or list of strings"); return values.stream().map(item -> ((String) item).strip()).filter(item -> !item.isEmpty()).toList(); }
  private static String publicBody(Note note) { if (note.publicCollection().equals("bibliography")) return MarkdownScanner.section(note.body(), "Конспект").orElse(""); if (!note.publicCollection().equals("concepts")) return note.body(); Matcher match = LEADING_H1.matcher(note.body()); if (!match.find() || match.group().startsWith("    ") || match.group().startsWith("\t")) return note.body(); String heading = ATX_CLOSER.matcher(match.group(1).strip()).replaceFirst(""); return heading.equals(title(note)) ? note.body().substring(match.end()).replaceFirst("^[\\r\\n]+", "") : note.body(); }
  private static String targetPath(Note note) { return note.publicCollection().equals("editorial") ? "src/data/pages/ru/" + note.publicId().strip() + ".json" : "src/content/" + note.publicCollection() + "/ru/" + note.publicId().strip() + ".md"; }
  private static String route(Note note) { if (note.publicCollection().equals("editorial")) return note.publicId().equals("home") ? "/ru/" : "/ru/" + note.publicId() + "/"; String section = switch (note.publicContentType()) { case "essay" -> "essays"; case "claim" -> "claims"; case "note" -> "notes"; case "album" -> "music"; case "book" -> "library"; case "concept" -> "concepts"; default -> throw new ManifestValidationException(note.vaultPath(), "publicContentType", "must be a supported publication type"); }; return "/ru/" + section + "/" + note.publicId() + "/"; }

  private static void musicMetadata(Note note, Map<String, Object> metadata) { Map<String, Object> fm = note.frontmatter(); metadata.put("reviewType", note.publicContentType()); metadata.put("artist", string(fm.get("artist"))); metadata.put("work", firstValid(note, "work", "albumTitle")); metadata.put("context", MarkdownScanner.section(note.body(), "Контекст записи").orElse("")); metadata.put("association", MarkdownScanner.section(note.body(), "Личная связь").orElse("")); metadata.put("listenFor", MarkdownScanner.listItems(note.body(), "Что слушать")); Object format = fm.get("format"); if (format != null) metadata.put("format", format); String release = normalizeDate(fm.get("releaseDate")); if (release != null) metadata.put("releaseDate", release); if (fm.containsKey("genreTags")) metadata.put("genreTags", stringList(note, "genreTags")); else metadata.put("genreTags", List.of()); for (String field : List.of("streamingUrl", "bandcampEmbedUrl")) if (fm.containsKey(field)) metadata.put(field, fm.get(field)); MarkdownScanner.section(note.body(), "Рекомендация как забота").ifPresent(value -> metadata.put("care", value)); }
  private static void bookMetadata(Note note, Map<String, Object> metadata) {
    Map<String, Object> frontmatter = note.frontmatter();
    Object authors = firstValidRaw(note, "authors", "author");
    List<?> authorValues = authors instanceof List<?> list ? list : List.of(authors);
    List<String> normalizedAuthors = new ArrayList<>();
    for (Object author : authorValues) normalizedAuthors.add(unwrap(author.toString()));
    metadata.put("authors", normalizedAuthors);

    Object publication = frontmatter.get("publication");
    if (publication != null) {
      metadata.put("publication", String.valueOf(publication).strip());
    } else {
      String publisher = string(frontmatter.get("publisher"));
      String published = string(frontmatter.get("published"));
      if (!publisher.isEmpty() || !published.isEmpty()) {
        metadata.put("publication", String.join(" · ", List.of(publisher, published).stream().filter(value -> !value.isEmpty()).toList()));
      }
    }
    for (String field : List.of("publicationDate", "start", "end")) {
      String value = normalizeDate(frontmatter.get(field));
      if (value != null) metadata.put(field, value);
    }
    if (frontmatter.containsKey("readingStatus")) {
      metadata.put("readingStatus", String.valueOf(frontmatter.get("readingStatus")).strip());
    } else if (frontmatter.containsKey("status")) {
      metadata.put("readingStatus", String.valueOf(frontmatter.get("status")).strip());
    }
    for (String field : List.of("use", "boundary", "selectedQuote")) {
      if (frontmatter.containsKey(field)) metadata.put(field, frontmatter.get(field));
    }
  }
  private static void claimMetadata(Note note, Map<String, Object> metadata) { Map<String, Object> fm = note.frontmatter(); metadata.put("statement", firstValid(note, "statement", "description")); for (String field : List.of("claimKinds", "supports", "opposes", "assumes", "refines", "contradicts", "sources")) if (fm.containsKey(field)) metadata.put(field, fm.get(field)); }
  private static String firstValid(Note note, String... fields) { return firstValidRaw(note, fields).toString().strip(); }
  private static Object firstValidRaw(Note note, String... fields) { for (String field : fields) { Object value = note.frontmatter().get(field); if (value instanceof String text && !text.strip().isEmpty()) return value; if (value instanceof List<?> list && list.stream().anyMatch(item -> item instanceof String text && !text.strip().isEmpty())) return value; } throw new ManifestValidationException(note.vaultPath(), String.join(" / ", fields), "must be a non-empty string"); }

  private static void validateEntry(Note note, Map<String, Object> metadata) {
    requireString(note, metadata, "id", "must be a non-empty string");
    requireString(note, metadata, "title", "must be a non-empty string");
    for (String field : List.of("date", "updated", "releaseDate", "publicationDate", "start", "end")) {
      validateDate(note, metadata, field);
    }
    List<?> topics = (List<?>) metadata.get("topics");
    List<String> unknown = topics.stream().map(Object::toString).filter(value -> !TOPICS.contains(value)).distinct().sorted().toList();
    if (!unknown.isEmpty()) throw new ManifestValidationException(note.vaultPath(), "topics", "contains unsupported values: " + String.join(", ", unknown));
    for (String field : List.of("description", "cover", "status")) optionalString(note, metadata, field);
    if (metadata.containsKey("foundational") && !(metadata.get("foundational") instanceof Boolean)) throw new ManifestValidationException(note.vaultPath(), "foundational", "must be a boolean");
    if (metadata.containsKey("readTime") && (!(metadata.get("readTime") instanceof Integer) || ((Integer) metadata.get("readTime")) <= 0)) throw new ManifestValidationException(note.vaultPath(), "readTime", "must be a positive integer");
    if (note.publicCollection().equals("music")) validateMusicEntry(note, metadata);
    if (note.publicCollection().equals("bibliography")) validateBibliographyEntry(note, metadata);
    if (note.publicCollection().equals("blog") && "claim".equals(metadata.get("contentType"))) validateClaimEntry(note, metadata);
  }
  private static void validateMusicEntry(Note note, Map<String, Object> metadata) {
    for (String field : List.of("artist", "work", "context", "association")) requireString(note, metadata, field, "must be a non-empty string");
    Object genres = metadata.get("genreTags");
    if (!(genres instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof String))) throw new ManifestValidationException(note.vaultPath(), "genreTags", "must be a list of strings");
    for (String field : List.of("format", "care")) optionalString(note, metadata, field);
    for (String field : List.of("streamingUrl", "bandcampEmbedUrl")) validateUrl(note, metadata, field);
  }
  private static void validateBibliographyEntry(Note note, Map<String, Object> metadata) {
    Object authors = metadata.get("authors");
    if (!(authors instanceof List<?> list) || list.isEmpty() || list.stream().anyMatch(value -> !(value instanceof String text) || text.strip().isEmpty())) throw new ManifestValidationException(note.vaultPath(), "authors", "must contain at least one non-empty string");
    for (String field : List.of("publication", "readingStatus", "use", "boundary")) optionalString(note, metadata, field);
    Object selectedQuote = metadata.get("selectedQuote");
    if (selectedQuote == null) return;
    if (!(selectedQuote instanceof Map<?, ?> quote)) throw new ManifestValidationException(note.vaultPath(), "selectedQuote", "must be an object");
    Object kind = quote.containsKey("kind") ? quote.get("kind") : "paraphrase";
    if (!("quote".equals(kind) || "paraphrase".equals(kind))) throw new ManifestValidationException(note.vaultPath(), "selectedQuote.kind", "must be quote or paraphrase");
    Object text = quote.get("text");
    if (!(text instanceof String value) || value.strip().isEmpty()) throw new ManifestValidationException(note.vaultPath(), "selectedQuote.text", "must be a non-empty string");
    if (quote.containsKey("locator") && !(quote.get("locator") instanceof String)) throw new ManifestValidationException(note.vaultPath(), "selectedQuote.locator", "must be a string");
  }
  private static void validateClaimEntry(Note note, Map<String, Object> metadata) {
    for (String field : List.of("claimKinds", "supports", "opposes", "assumes", "refines", "contradicts")) {
      Object value = metadata.getOrDefault(field, List.of());
      if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) throw new ManifestValidationException(note.vaultPath(), field, "must be a list of strings");
    }
    Object sources = metadata.getOrDefault("sources", List.of());
    if (!(sources instanceof List<?> list)) throw new ManifestValidationException(note.vaultPath(), "sources", "must be a list");
    for (int index = 0; index < list.size(); index++) if (!(list.get(index) instanceof Map<?, ?>)) throw new ManifestValidationException(note.vaultPath(), "sources[" + index + "]", "must be an object");
  }
  private static void requireString(Note note, Map<String, Object> metadata, String field, String reason) { if (!(metadata.get(field) instanceof String value) || value.strip().isEmpty()) throw new ManifestValidationException(note.vaultPath(), field, reason); }
  private static void optionalString(Note note, Map<String, Object> metadata, String field) { if (metadata.containsKey(field) && !(metadata.get(field) instanceof String)) throw new ManifestValidationException(note.vaultPath(), field, "must be a string"); }
  private static void validateDate(Note note, Map<String, Object> metadata, String field) { Object value = metadata.get(field); if (value == null) return; if (!(value instanceof String text)) throw new ManifestValidationException(note.vaultPath(), field, "must be a YYYY-MM-DD string"); try { LocalDate.parse(text); } catch (DateTimeParseException error) { throw new ManifestValidationException(note.vaultPath(), field, "must be a real YYYY-MM-DD date"); } }
  private static void validateUrl(Note note, Map<String, Object> metadata, String field) { Object value = metadata.get(field); if (value == null) return; if (!(value instanceof String text) || !text.matches("https?://[^/]+(?:/.*)?")) throw new ManifestValidationException(note.vaultPath(), field, "must be an http(s) URL"); }

  private void resolveEditorialMetadata(
      String path,
      Map<String, Object> metadata,
      List<Note> notes,
      List<ManifestLink> retained,
      List<ManifestLink> stripped) {
    for (Map.Entry<String, Object> entry : new ArrayList<>(metadata.entrySet())) {
      if (!entry.getKey().equals("title")) {
        metadata.put(entry.getKey(), tokenizeEditorial(path, entry.getKey(), entry.getValue(), notes, retained, stripped));
      }
    }
    resolveCurrentTargets(path, metadata, notes, retained);
  }

  @SuppressWarnings("unchecked")
  private static void resolveCurrentTargets(
      String path, Map<String, Object> metadata, List<Note> notes, List<ManifestLink> retained) {
    if (!(metadata.get("current") instanceof List<?> current)) {
      return;
    }
    for (int index = 0; index < current.size(); index++) {
      if (!(current.get(index) instanceof Map<?, ?> raw)) {
        throw new ManifestValidationException(path, "current[" + index + "]", "must be an object");
      }
      Map<String, Object> item = (Map<String, Object>) raw;
      if (!item.containsKey("target")) {
        continue;
      }
      Object rawTarget = item.get("target");
      if (!(rawTarget instanceof String target) || target.strip().isEmpty()) {
        throw new ManifestValidationException(path, "current[" + index + "].target", "must be a non-empty string");
      }
      Note resolved = resolveNote(notes, target, path, "current[" + index + "].target");
      if (resolved == null) {
        throw new ManifestValidationException(path, "current[" + index + "].target", "must resolve to exactly one published entry");
      }
      Object layout = item.get("layout");
      if ("book".equals(layout) && !"book".equals(resolved.publicContentType())) {
        throw new ManifestValidationException(path, "current[" + index + "].target", "must reference a book");
      }
      if ("album".equals(layout) && !"album".equals(resolved.publicContentType())) {
        throw new ManifestValidationException(path, "current[" + index + "].target", "must reference an album");
      }
      item.put("target", resolved.publicId());
      retained.add(new ManifestLink(path, target, "editorial", resolved.publicId(), route(resolved)));
    }
  }

  private Object tokenizeEditorial(
      String path,
      String key,
      Object value,
      List<Note> notes,
      List<ManifestLink> retained,
      List<ManifestLink> stripped) {
    if (value instanceof String text) {
      return tokenizeEditorialText(path, key, text, notes, retained, stripped);
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>();
      for (Object item : list) {
        result.add(tokenizeEditorial(path, key, item, notes, retained, stripped));
      }
      return result;
    }
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> item : map.entrySet()) {
        String nestedKey = item.getKey().toString();
        result.put(nestedKey, tokenizeEditorial(path, nestedKey, item.getValue(), notes, retained, stripped));
      }
      return result;
    }
    return value;
  }

  private Object tokenizeEditorialText(
      String path,
      String key,
      String text,
      List<Note> notes,
      List<ManifestLink> retained,
      List<ManifestLink> stripped) {
    if (STRUCTURAL_EDITORIAL_FIELDS.contains(key) || !text.contains("[[")) {
      return text;
    }
    try {
      dev.eugene.astroexport.links.ManifestLink tokens = linkProcessor.tokenizeEditorialText(text, notes);
      for (LinkProcessor.ResolvedLink link : tokens.retained()) {
        retained.add(new ManifestLink(path, link.target(), "editorial-text", link.publicId(), link.route()));
      }
      for (String target : tokens.stripped()) {
        stripped.add(new ManifestLink(path, target, "editorial-text"));
      }
      return tokens.body();
    } catch (LinkProcessor.ManifestValidationException error) {
      throw new ManifestValidationException(
          path,
          "editorial text link " + linkTarget(error.getMessage()),
          "is ambiguous; use a publicId or vault path");
    }
  }

  @SuppressWarnings("unchecked")
  private void resolvePins(String path, Map<String, Object> metadata, List<Note> notes) {
    Object raw = metadata.get("showcase");
    if (!(raw instanceof List<?> showcases)) {
      return;
    }
    List<String> pins = new ArrayList<>();
    String expected = Map.of(
        "notes", "note",
        "library", "book",
        "music", "album",
        "essays", "essay",
        "claims", "claim",
        "concepts", "concept").get(metadata.get("id"));
    for (int index = 0; index < showcases.size(); index++) {
      if (!(showcases.get(index) instanceof Map<?, ?> rawShowcase) || !rawShowcase.containsKey("target")) {
        throw new ManifestValidationException(path, "showcase[" + index + "]", "must contain exactly target and text");
      }
      Map<String, Object> showcase = (Map<String, Object>) rawShowcase;
      String target = String.valueOf(showcase.get("target"));
      String targetField = "showcase[" + index + "].target";
      Note resolved = resolveShowcaseTarget(notes, target, path, targetField);
      if (expected != null && !expected.equals(resolved.publicContentType())) {
        throw new ManifestValidationException(path, targetField, "must reference a " + expected);
      }
      if (!pins.add(resolved.publicId())) {
        throw new ManifestValidationException(path, targetField, "must not duplicate an earlier pin");
      }
      showcase.put("target", resolved.publicId());
    }
    metadata.put("pinned", pins);
  }

  private static Note resolveShowcaseTarget(List<Note> notes, String target, String path, String field) {
    try {
      Note resolved = resolveNote(notes, target, path, field);
      if (resolved != null) {
        return resolved;
      }
    } catch (ManifestValidationException ignored) {
      // Showcase references intentionally share one public diagnostic for absent and ambiguous targets.
    }
    throw new ManifestValidationException(path, field, "must resolve to exactly one published entry");
  }
  static void filterEditorialReferences(String path, Map<String, Object> metadata, List<Note> notes, List<ManifestLink> stripped) {
    Set<String> publicIds = notes.stream().map(Note::publicId).collect(java.util.stream.Collectors.toSet());
    for (String field : List.of("featured", "primary")) {
      if (!metadata.containsKey(field) || publicIds.contains(metadata.get(field))) continue;
      String target = String.valueOf(metadata.remove(field));
      String pageId = String.valueOf(metadata.get("id"));
      if (field.equals("featured") && pageId.equals("home")) {
        for (String dependent : List.of("featuredLabel", "featuredTitle", "featuredText", "featuredTraceAlt")) metadata.remove(dependent);
      }
      if (field.equals("primary") && pageId.equals("concepts")) metadata.remove("primaryLabel");
      stripped.add(new ManifestLink(path, target, "editorial"));
    }
    for (String field : List.of("selected", "items")) {
      if (!(metadata.get(field) instanceof List<?> values)) continue;
      List<Object> retained = new ArrayList<>();
      for (Object value : values) {
        if (publicIds.contains(value)) retained.add(value);
        else stripped.add(new ManifestLink(path, String.valueOf(value), "editorial"));
      }
      metadata.put(field, retained);
    }
    for (String field : List.of("paths", "routes")) {
      if (!(metadata.get(field) instanceof List<?> values)) continue;
      List<Object> retained = new ArrayList<>();
      for (Object value : values) {
        if (!(value instanceof Map<?, ?> object) || !object.containsKey("route")) {
          retained.add(value);
          continue;
        }
        Object route = object.get("route");
        if (publicIds.contains(route)) retained.add(value);
        else stripped.add(new ManifestLink(path, String.valueOf(route), "editorial"));
      }
      metadata.put(field, retained);
    }
  }
  private static void resolveFrontmatterLinks(String path, Map<String, Object> metadata, List<Note> notes, List<ManifestLink> retained, List<ManifestLink> stripped) {
    Object raw = metadata.get("links");
    if (!(raw instanceof List<?> links)) {
      return;
    }
    Map<String, Note> byPublicId = new LinkedHashMap<>();
    for (Note note : notes) {
      byPublicId.put(note.publicId(), note);
    }
    List<String> kept = new ArrayList<>();
    for (Object value : links) {
      String target = value.toString();
      Note resolved = byPublicId.get(target);
      if (resolved == null) {
        stripped.add(new ManifestLink(path, target, "frontmatter"));
        continue;
      }
      kept.add(resolved.publicId());
      retained.add(new ManifestLink(path, target, "frontmatter", resolved.publicId(), route(resolved)));
    }
    metadata.put("links", kept);
  }
  private void resolveClaimLinks(
      String path,
      Map<String, Object> metadata,
      List<Note> notes,
      List<ManifestLink> retained,
      List<ManifestLink> stripped) {
    for (String field : List.of("supports", "opposes", "assumes", "refines", "contradicts")) {
      if (!(metadata.get(field) instanceof List<?> values)) {
        continue;
      }
      List<Object> result = new ArrayList<>();
      for (int index = 0; index < values.size(); index++) {
        result.add(resolveClaimReference(path, values.get(index), notes, retained, stripped, field + "[" + index + "]"));
      }
      metadata.put(field, result);
    }
    if (!(metadata.get("sources") instanceof List<?> values)) return;
    List<Map<String, Object>> sources = new ArrayList<>();
    for (int index = 0; index < values.size(); index++) {
      if (!(values.get(index) instanceof Map<?, ?> raw)) throw new ManifestValidationException(path, "sources[" + index + "]", "must be an object");
      LinkedHashMap<String, Object> source = new LinkedHashMap<>();
      raw.forEach((key, value) -> source.put(key.toString(), value));
      if (source.containsKey("link")) {
        source.put("link", resolveClaimReference(path, source.get("link"), notes, retained, stripped, "sources[" + index + "].link"));
      }
      for (String field : List.of("evidence", "locator")) {
        if (source.containsKey(field)) {
          source.put(field, tokenizeClaimText(path, source.get(field), notes, retained, stripped, "sources[" + index + "]." + field));
        }
      }
      sources.add(source);
    }
    metadata.put("sources", sources);
  }
  private Object tokenizeClaimText(
      String path,
      Object value,
      List<Note> notes,
      List<ManifestLink> retained,
      List<ManifestLink> stripped,
      String field) {
    if (!(value instanceof String text)) {
      throw new ManifestValidationException(path, field, "must be a string");
    }
    try {
      dev.eugene.astroexport.links.ManifestLink tokens = linkProcessor.tokenizeEditorialText(text, notes);
      for (LinkProcessor.ResolvedLink link : tokens.retained()) {
        retained.add(new ManifestLink(path, link.target(), "frontmatter", link.publicId(), link.route()));
      }
      for (String target : tokens.stripped()) {
        stripped.add(new ManifestLink(path, target, "frontmatter"));
      }
      return tokens.body();
    } catch (LinkProcessor.ManifestValidationException error) {
      throw new ManifestValidationException(
          path,
          "frontmatter link " + linkTarget(error.getMessage()),
          "is ambiguous; use a publicId or vault path");
    }
  }

  private Object resolveClaimReference(
      String path,
      Object value,
      List<Note> notes,
      List<ManifestLink> retained,
      List<ManifestLink> stripped,
      String field) {
    if (!(value instanceof String source) || source.strip().isEmpty()) {
      throw new ManifestValidationException(path, field, "must be a non-empty string");
    }
    String reference = source.strip();
    if (SCALAR_EMBED.matcher(reference).matches()) {
      throw new ManifestValidationException(path, field, "must not be an embed");
    }
    Matcher match = SCALAR_LINK.matcher(reference);
    if (!match.matches()) {
      return Map.of("label", reference);
    }
    String target = match.group(1).strip();
    Note resolved = resolveNote(notes, target, path, "frontmatter link " + target);
    String label = match.group(2) == null
        ? target.substring(target.lastIndexOf('/') + 1)
        : match.group(2).strip();
    if (resolved == null) {
      stripped.add(new ManifestLink(path, target, "frontmatter"));
      return Map.of("label", label);
    }
    retained.add(new ManifestLink(path, target, "frontmatter", resolved.publicId(), route(resolved)));
    return Map.of("label", label, "target", resolved.publicId());
  }

  private static Note resolveNote(List<Note> notes, String target, String path, String field) {
    List<Note> exact = notes.stream()
        .filter(note -> target.equals(note.publicId()) || target.equals(note.vaultPath().replaceFirst("\\.md$", "")))
        .toList();
    List<Note> candidates = exact.isEmpty()
        ? notes.stream()
            .filter(note -> target.equals(note.title()) || target.equals(string(note.frontmatter().get("title"))) || note.aliases().contains(target))
            .toList()
        : exact;
    if (candidates.size() > 1) {
      throw new ManifestValidationException(path, field, "is ambiguous; use a publicId or vault path");
    }
    return candidates.isEmpty() ? null : candidates.getFirst();
  }

  private static String dateFromId(Object value) { if (value == null) return null; String id = value.toString(); return id.matches("\\d{8}.*") ? id.substring(0, 4) + "-" + id.substring(4, 6) + "-" + id.substring(6, 8) : null; }
  private static String normalizeDate(Object value) { if (value == null || value.toString().isBlank()) return null; String text = unwrap(value.toString()); return text; }
  private static String unwrap(String value) { Matcher match = SCALAR_LINK.matcher(value.strip()); return match.matches() ? (match.group(2) == null ? match.group(1).strip() : match.group(2).strip()) : value.strip(); }
  private static String string(Object value) { return value == null ? "" : value.toString().strip(); }
  private static String bookDescription(String body) { Matcher match = BOOK_DESCRIPTION.matcher(body); return match.find() ? HTML_TAG.matcher(match.group(1)).replaceAll("").replaceAll("\\s+", " ").strip() : ""; }
  private static String linkTarget(String message) { return message.replaceFirst("^ambiguous public link: ", ""); }
  static String sourceHash(Map<String, Object> metadata, String body) { LinkedHashMap<String, Object> values = new LinkedHashMap<>(metadata); values.remove("sourceHash"); try { String json = pythonJson(values); MessageDigest digest = MessageDigest.getInstance("SHA-256"); return java.util.HexFormat.of().formatHex(digest.digest((json + "\n" + body).getBytes(StandardCharsets.UTF_8))); } catch (Exception error) { throw new IllegalStateException("cannot hash manifest source", error); } }
  private static String pythonJson(Object value) throws Exception { if (value == null || value instanceof String || value instanceof Character || value instanceof Boolean || value instanceof Number) return HASH_JSON.writeValueAsString(value); if (value instanceof Map<?, ?> map) { List<String> entries = new ArrayList<>(); map.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString())).forEach(entry -> { try { entries.add(pythonJson(entry.getKey().toString()) + ": " + pythonJson(entry.getValue())); } catch (Exception error) { throw new JsonEncodingException(error); } }); return "{" + String.join(", ", entries) + "}"; } if (value instanceof List<?> list) { List<String> values = new ArrayList<>(); for (Object item : list) values.add(pythonJson(item)); return "[" + String.join(", ", values) + "]"; } return HASH_JSON.writeValueAsString(value.toString()); }
  private static final class JsonEncodingException extends RuntimeException { JsonEncodingException(Exception cause) { super(cause); } }

  public static final class ManifestValidationException extends IllegalArgumentException {
    private final String sourcePath;
    private final String fieldName;
    private final String reason;

    public ManifestValidationException(String sourcePath, String fieldName, String reason) {
      super(sourcePath + ": " + fieldName + " " + reason);
      this.sourcePath = sourcePath;
      this.fieldName = fieldName;
      this.reason = reason;
    }

    public String sourcePath() {
      return sourcePath;
    }

    public String fieldName() {
      return fieldName;
    }

    public String reason() {
      return reason;
    }
  }

  public static final class ManifestTransclusionException extends IllegalArgumentException {
    public ManifestTransclusionException(String sourcePath, String target) {
      super(sourcePath + ": unpublished transclusion " + target);
    }
  }
}
