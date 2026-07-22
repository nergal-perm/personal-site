package dev.eugene.astroexport.validation;

import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.Note;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;

public final class PreflightService {
  private static final Pattern PUBLISH_TRUE_LINE = Pattern.compile("(?m)^publish:[ \\t]+true[ \\t]*$");
  private static final Pattern WIKILINK = Pattern.compile("(?<embed>!?)\\[\\[(?<target>[^\\]|#]+)(?<heading>#[^\\]|]*)?(?:\\|(?<label>[^\\]]+))?\\]\\]");
  private static final Pattern SHOWCASE_SECTION = Pattern.compile(
      "(?ms)^##[ \\t]+Витрина[ \\t]*(?:\\r?\\n|\\z)(.*?)(?=^##[ \\t]+[^\\r\\n]*(?:\\r?\\n|\\z)|\\z)");
  private static final Pattern SHOWCASE_ITEM = Pattern.compile(
      "(?ms)^###[ \\t]+\\[\\[(?<target>[^\\]|#]+)(?:#[^\\]|]*)?(?:\\|[^\\]]+)?\\]\\][ \\t]*(?:\\r?\\n|\\z)(?<text>.*?)(?=^###[ \\t]+|\\z)");
  private static final Set<String> TOPICS = Set.of(
      "systems", "software", "ai-work", "thinking", "reading", "music", "personal-systems");
  private final PublicationValidator validator;

  public PreflightService() { this(new PublicationValidator()); }
  public PreflightService(PublicationValidator validator) { this.validator = validator; }

  public Result preflight(Path vault, String notePath) {
    Loaded loaded = load(vault, notePath);
    if (loaded.error() != null) return failure(loaded.error());
    Note note = loaded.note();
    List<PublicationDiagnostic> contract = prefixed(note.vaultPath(), validator.validate(note));
    if (!contract.isEmpty()) return new Result(note, Optional.empty(), contract, List.of());

    Selection selection = select(vault);
    List<PublicationDiagnostic> workspaceHealth = selection.exclusions().stream()
        .filter(item -> !item.vaultPath().equals(note.vaultPath()))
        .map(item -> new PublicationDiagnostic(item.field(), item.vaultPath() + ": " + item.reason()))
        .toList();
    List<Exclusion> activeExclusions = selection.exclusions().stream()
        .filter(item -> item.vaultPath().equals(note.vaultPath())).toList();
    if (!activeExclusions.isEmpty()) {
      return new Result(note, Optional.empty(), activeExclusions.stream()
          .map(item -> new PublicationDiagnostic(item.field(), item.vaultPath() + ": " + item.reason())).toList(), workspaceHealth);
    }
    if (selection.included().stream().noneMatch(item -> item.vaultPath().equals(note.vaultPath()))) {
      return new Result(note, Optional.empty(), List.of(new PublicationDiagnostic("selection",
          note.vaultPath() + ": must be selected for publication")), workspaceHealth);
    }
    try {
      return new Result(note, Optional.of(buildEntry(note, selection.included())), List.of(), workspaceHealth);
    } catch (ManifestFailure failure) {
      return new Result(note, Optional.empty(), List.of(new PublicationDiagnostic(failure.field(),
          note.vaultPath() + ": " + failure.reason())), workspaceHealth);
    }
  }

  private Selection select(Path vault) {
    List<Note> candidates = new ArrayList<>();
    List<Exclusion> exclusions = new ArrayList<>();
    try (var paths = Files.walk(vault)) {
      for (Path path : paths.filter(path -> path.toString().endsWith(".md")).sorted().toList()) {
        String source;
        try { source = Files.readString(path, StandardCharsets.UTF_8); } catch (IOException ignored) { continue; }
        if (!PUBLISH_TRUE_LINE.matcher(source).find()) continue;
        String vaultPath = vault.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        try {
          FrontmatterDocument document = FrontmatterDocument.parse(path, vaultPath, source);
          Note candidate = note(path, vaultPath, document);
          if (!candidate.publish()) continue;
          List<PublicationDiagnostic> diagnostics = validator.validate(candidate);
          if (diagnostics.isEmpty()) candidates.add(candidate);
          else diagnostics.forEach(item -> exclusions.add(new Exclusion(vaultPath, item.field(), item.message())));
        } catch (RuntimeException exception) {
          exclusions.add(new Exclusion(vaultPath, "frontmatter", "invalid frontmatter: " + exception.getMessage()));
        }
      }
    } catch (IOException exception) {
      throw new IllegalStateException("could not search vault", exception);
    }
    Map<String, Long> idCounts = candidates.stream().collect(java.util.stream.Collectors.groupingBy(Note::publicId,
        LinkedHashMap::new, java.util.stream.Collectors.counting()));
    List<Note> included = new ArrayList<>();
    for (Note candidate : candidates) {
      if (idCounts.get(candidate.publicId()) == 1) included.add(candidate);
      else exclusions.add(new Exclusion(candidate.vaultPath(), "publicId", "duplicate publicId " + candidate.publicId()));
    }
    exclusions.sort(Comparator.comparing(Exclusion::vaultPath));
    return new Selection(List.copyOf(included), List.copyOf(exclusions));
  }

  private ManifestEntry buildEntry(Note note, List<Note> publicNotes) {
    Map<String, LinkResolution> links = publicIndex(publicNotes);
    List<String> topics = strings(note.frontmatter().get("topics"));
    List<String> unsupported = topics.stream().filter(topic -> !TOPICS.contains(topic)).sorted().toList();
    if (!unsupported.isEmpty()) throw new ManifestFailure("topics", "contains unsupported values: " + String.join(", ", unsupported));

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("id", note.publicId());
    metadata.put("title", string(note.frontmatter().get("title"), note.title()));
    metadata.put("publish", true);
    if (note.publicCollection().equals("blog")) metadata.put("contentType", note.publicContentType());
    String body = resolveBody(note, links);
    if (note.publicCollection().equals("blog") && note.publicContentType().equals("claim")) {
      for (String field : List.of("supports", "opposes", "assumes", "refines", "contradicts")) {
        if (note.frontmatter().containsKey(field)) metadata.put(field, resolveClaimReferences(note.frontmatter().get(field), links));
      }
    }
    if (note.publicCollection().equals("editorial") && note.publicContentType().equals("curated_page")
        && "essays".equals(note.frontmatter().get("editorialPage"))) {
      addEssaysShowcase(metadata, note.body(), links);
    }
    return new ManifestEntry(note.vaultPath(), targetPath(note), route(note), metadata, body);
  }

  private static String resolveBody(Note note, Map<String, LinkResolution> links) {
    var matcher = WIKILINK.matcher(note.body());
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      if (MarkdownProtection.contains(note.body(), matcher.start())) {
        matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(matcher.group()));
        continue;
      }
      String target = matcher.group("target").strip();
      LinkResolution resolution = links.get(target);
      if (resolution != null && resolution.ambiguous()) throw new ManifestFailure("link", "public link " + target + " is ambiguous");
      PublicLink link = resolution == null ? null : resolution.link();
      if (matcher.group("embed").equals("!") && link == null) {
        throw new ManifestFailure("transclusion", "unpublished transclusion " + target);
      }
      if (link == null) {
        matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(label(matcher, target)));
      } else if (matcher.group("embed").equals("!")) {
        matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(matcher.group()));
      } else {
        matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement("[" + label(matcher, target) + "](" + link.route() + headingFragment(matcher.group("heading")) + ")"));
      }
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static List<Map<String, String>> resolveClaimReferences(Object value, Map<String, LinkResolution> links) {
    List<Map<String, String>> result = new ArrayList<>();
    for (String item : strings(value)) {
      var match = WIKILINK.matcher(item.trim());
      if (match.matches() && match.group("embed").isEmpty()) {
        String target = match.group("target").strip();
        Map<String, String> reference = new LinkedHashMap<>();
        reference.put("label", label(match, target));
        LinkResolution resolution = links.get(target);
        if (resolution != null && resolution.ambiguous()) throw new ManifestFailure("link", "public link " + target + " is ambiguous");
        PublicLink link = resolution == null ? null : resolution.link();
        if (link != null) reference.put("target", link.publicId());
        result.add(reference);
      } else result.add(Map.of("label", item));
    }
    return List.copyOf(result);
  }

  private static void addEssaysShowcase(Map<String, Object> metadata, String body, Map<String, LinkResolution> links) {
    var section = SHOWCASE_SECTION.matcher(body);
    if (!section.find()) return;
    var items = SHOWCASE_ITEM.matcher(section.group(1));
    List<String> pinned = new ArrayList<>();
    List<Map<String, Object>> showcase = new ArrayList<>();
    while (items.find()) {
      String target = items.group("target").strip();
      LinkResolution resolution = links.get(target);
      if (resolution != null && resolution.ambiguous()) throw new ManifestFailure("link", "public link " + target + " is ambiguous");
      PublicLink link = resolution == null ? null : resolution.link();
      if (link == null) throw new ManifestFailure("showcase", "must reference a published entry");
      pinned.add(link.publicId());
      showcase.add(Map.of("target", link.publicId(), "text", editorialTokens(items.group("text").strip(), links)));
    }
    metadata.put("pinned", List.copyOf(pinned));
    metadata.put("showcase", List.copyOf(showcase));
  }

  private static List<Map<String, String>> editorialTokens(String text, Map<String, LinkResolution> links) {
    List<Map<String, String>> tokens = new ArrayList<>();
    var matcher = WIKILINK.matcher(text);
    int cursor = 0;
    while (matcher.find()) {
      appendText(tokens, text.substring(cursor, matcher.start()));
      LinkResolution resolution = links.get(matcher.group("target").strip());
      if (resolution != null && resolution.ambiguous()) throw new ManifestFailure("link", "public link " + matcher.group("target").strip() + " is ambiguous");
      PublicLink link = resolution == null ? null : resolution.link();
      if (link == null) appendText(tokens, label(matcher, matcher.group("target").strip()));
      else tokens.add(Map.of("kind", "reference", "target", link.publicId()));
      cursor = matcher.end();
    }
    appendText(tokens, text.substring(cursor));
    return List.copyOf(tokens);
  }

  private static void appendText(List<Map<String, String>> tokens, String value) {
    if (value.isEmpty()) return;
    if (!tokens.isEmpty() && tokens.getLast().get("kind").equals("text")) {
      Map<String, String> previous = new LinkedHashMap<>(tokens.removeLast());
      previous.put("value", previous.get("value") + value);
      tokens.add(previous);
    } else tokens.add(Map.of("kind", "text", "value", value));
  }

  private static Map<String, LinkResolution> publicIndex(List<Note> notes) {
    Map<String, PublicLink> exact = new LinkedHashMap<>();
    Map<String, Map<String, PublicLink>> descriptive = new LinkedHashMap<>();
    for (Note note : notes) {
      PublicLink link = new PublicLink(note.publicId(), route(note));
      exact.put(note.publicId(), link);
      exact.put(note.vaultPath().replaceFirst("\\.md$", ""), link);
      for (String key : descriptiveKeys(note)) {
        if (!key.isEmpty()) descriptive.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(link.publicId(), link);
      }
    }
    Map<String, LinkResolution> index = new LinkedHashMap<>();
    exact.forEach((key, link) -> index.put(key, new LinkResolution(link, false)));
    descriptive.forEach((key, candidates) -> {
      if (!index.containsKey(key)) index.put(key, new LinkResolution(candidates.size() == 1 ? candidates.values().iterator().next() : null,
          candidates.size() > 1));
    });
    return index;
  }

  private static List<String> descriptiveKeys(Note note) {
    List<String> keys = new ArrayList<>();
    keys.add(note.title());
    keys.add(string(note.frontmatter().get("title"), ""));
    keys.addAll(note.aliases());
    return keys;
  }

  private static String targetPath(Note note) {
    return note.publicCollection().equals("editorial") ? "src/data/pages/ru/" + note.publicId() + ".json"
        : "src/content/" + note.publicCollection() + "/ru/" + note.publicId() + ".md";
  }

  private static String route(Note note) {
    if (note.publicCollection().equals("editorial")) return note.publicId().equals("home") ? "/ru/" : "/ru/" + note.publicId() + "/";
    String section = switch (note.publicContentType()) {
      case "essay" -> "essays"; case "claim" -> "claims"; case "note" -> "notes"; case "album" -> "music";
      case "book" -> "library"; case "concept" -> "concepts"; default -> throw new IllegalArgumentException("unsupported content type");
    };
    return "/ru/" + section + "/" + note.publicId() + "/";
  }

  private Loaded load(Path vault, String notePath) {
    Path relative;
    try { relative = Path.of(notePath); } catch (RuntimeException exception) { return Loaded.error("path", notePath + ": must be a vault-relative .md path"); }
    if (relative.isAbsolute() || !notePath.endsWith(".md") || containsTraversal(relative)) return Loaded.error("path", notePath + ": must be a vault-relative .md path");
    Path resolvedVault = vault.toAbsolutePath().normalize();
    Path candidate = resolvedVault.resolve(relative).normalize();
    if (!candidate.startsWith(resolvedVault)) return Loaded.error("path", notePath + ": must be a vault-relative .md path");
    try {
      if (!Files.isRegularFile(candidate)) return Loaded.error("path", notePath + ": does not exist");
      if (!candidate.toRealPath().startsWith(resolvedVault.toRealPath())) return Loaded.error("path", notePath + ": must be a vault-relative .md path");
      return Loaded.note(note(candidate, notePath, FrontmatterDocument.parse(candidate, notePath, Files.readString(candidate, StandardCharsets.UTF_8))));
    } catch (IOException exception) { return Loaded.error("path", notePath + ": " + exception.getMessage());
    } catch (RuntimeException exception) { return Loaded.error("frontmatter", notePath + ": invalid frontmatter: " + exception.getMessage()); }
  }

  private static Note note(Path path, String vaultPath, FrontmatterDocument document) {
    Map<String, Object> metadata = new LinkedHashMap<>(document.metadata());
    if (metadata.get("publish") instanceof String value) {
      if (value.equals("True")) metadata.put("publish", true);
      else if (value.equals("False")) metadata.put("publish", false);
    }
    return new Note(path, vaultPath, path.getFileName().toString().replaceFirst("\\.md$", ""), metadata, document.body(),
        Boolean.TRUE.equals(metadata.get("publish")), string(metadata.get("publicId"), ""), string(metadata.get("publicCollection"), ""), string(metadata.get("publicContentType"), ""), aliases(metadata.get("aliases")));
  }

  private static String string(Object value, String fallback) { return value instanceof String text && !text.strip().isEmpty() ? text.strip() : fallback; }
  private static List<String> strings(Object value) {
    if (value == null) return List.of();
    if (value instanceof String text) return text.strip().isEmpty() ? List.of() : List.of(text.strip());
    if (value instanceof List<?> values) return values.stream().filter(String.class::isInstance).map(String.class::cast).map(String::strip).filter(item -> !item.isEmpty()).toList();
    return List.of();
  }
  private static List<String> aliases(Object value) { return strings(value); }
  private static String label(java.util.regex.Matcher matcher, String target) { return matcher.group("label") == null ? target.substring(target.lastIndexOf('/') + 1).strip() : matcher.group("label").strip(); }
  private static String headingFragment(String heading) {
    if (heading == null || heading.isBlank()) return "";
    String value = heading.substring(1).strip().toLowerCase(Locale.ROOT).replaceAll("[^\\w\\s-]", "")
        .replaceAll("[\\s_-]+", "-").replaceAll("^-+|-+$", "");
    return value.isEmpty() ? "" : "#" + value;
  }
  private static List<PublicationDiagnostic> prefixed(String path, List<PublicationDiagnostic> diagnostics) { return diagnostics.stream().map(item -> new PublicationDiagnostic(item.field(), path + ": " + item.message(), item.blocking())).toList(); }
  private static boolean containsTraversal(Path path) { for (Path part : path) if (part.toString().equals("..")) return true; return false; }
  private static Result failure(Error error) { return new Result(null, Optional.empty(), List.of(new PublicationDiagnostic(error.field(), error.message())), List.of()); }

  public record Result(Note note, Optional<ManifestEntry> entry, List<PublicationDiagnostic> diagnostics, List<PublicationDiagnostic> workspaceHealth) {
    public Result { entry = entry == null ? Optional.empty() : entry; diagnostics = List.copyOf(diagnostics); workspaceHealth = List.copyOf(workspaceHealth); }
    public boolean ready() { return entry.isPresent() && diagnostics.stream().noneMatch(PublicationDiagnostic::blocking); }
  }
  private record Selection(List<Note> included, List<Exclusion> exclusions) { }
  private record Exclusion(String vaultPath, String field, String reason) { }
  private record PublicLink(String publicId, String route) { }
  private record LinkResolution(PublicLink link, boolean ambiguous) { }
  private static final class ManifestFailure extends RuntimeException {
    private final String field;
    private final String reason;

    private ManifestFailure(String field, String reason) {
      this.field = field;
      this.reason = reason;
    }

    private String field() { return field; }
    private String reason() { return reason; }
  }
  private record Error(String field, String message) { }
  private record Loaded(Note note, Error error) { static Loaded note(Note note) { return new Loaded(note, null); } static Loaded error(String field, String message) { return new Loaded(null, new Error(field, message)); } }
}
