package dev.eugene.astroexport.links;

import dev.eugene.astroexport.markdown.MarkdownScanner;
import dev.eugene.astroexport.model.Note;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LinkProcessor {
  private static final Pattern WIKILINK = Pattern.compile("(!?)\\[\\[([^\\]|#]+)(#[^\\]|]*)?(?:\\|([^\\]]+))?\\]\\]");
  private static final Pattern TIMESTAMP = Pattern.compile("^\\d{12}\\s+");
  private static final Collection<String> ASSET_EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".mp3", ".mp4");

  public ManifestLink processLinks(Note note, Collection<Note> selectedNotes) {
    Map<String, Resolution> index = index(selectedNotes);
    String body = MarkdownScanner.stripObsidianComments(note.body());
    List<ResolvedLink> retained = new ArrayList<>();
    List<String> stripped = new ArrayList<>();
    List<String> assets = new ArrayList<>();
    String replaced = replaceOutsideProtectedContexts(body, match -> {
      String target = match.group(2).strip();
      boolean embed = !match.group(1).isEmpty();
      if (isAsset(target)) {
        if (embed) assets.add(target);
        return match.group();
      }
      PublicLink link = resolve(index, target);
      if (link != null) {
        retained.add(new ResolvedLink(target, link.publicId(), link.route()));
        if (embed) return match.group();
        return "[" + label(match, target) + "](" + link.route() + headingFragment(match.group(3)) + ")";
      }
      if (embed) throw new TransclusionException(target);
      stripped.add(target);
      return label(match, target);
    });
    return new ManifestLink(replaced, retained, stripped, assets);
  }

  public ManifestLink tokenizeEditorialText(String source, Collection<Note> selectedNotes) {
    Map<String, Resolution> index = index(selectedNotes);
    String body = MarkdownScanner.stripObsidianComments(source);
    List<Map<String, String>> tokens = new ArrayList<>();
    List<ResolvedLink> retained = new ArrayList<>();
    List<String> stripped = new ArrayList<>();
    int cursor = 0;
    for (MarkdownScanner.Span span : MarkdownScanner.protectedSpans(body)) {
      tokenize(body.substring(cursor, span.start()), index, tokens, retained, stripped);
      appendText(tokens, body.substring(span.start(), span.end()));
      cursor = span.end();
    }
    tokenize(body.substring(cursor), index, tokens, retained, stripped);
    return new ManifestLink(List.copyOf(tokens), retained, stripped, List.of());
  }

  private static void tokenize(String source, Map<String, Resolution> index, List<Map<String, String>> tokens,
                               List<ResolvedLink> retained, List<String> stripped) {
    Matcher matcher = WIKILINK.matcher(source);
    int cursor = 0;
    while (matcher.find()) {
      appendText(tokens, source.substring(cursor, matcher.start()));
      String target = matcher.group(2).strip();
      PublicLink link = resolve(index, target);
      if (!matcher.group(1).isEmpty()) {
        appendText(tokens, matcher.group());
      } else if (link != null) {
        retained.add(new ResolvedLink(target, link.publicId(), link.route()));
        tokens.add(Map.of("kind", "reference", "target", link.publicId()));
      } else {
        stripped.add(target);
        appendText(tokens, label(matcher, target));
      }
      cursor = matcher.end();
    }
    appendText(tokens, source.substring(cursor));
  }

  private static String replaceOutsideProtectedContexts(String body, Function<Matcher, String> replacement) {
    StringBuilder result = new StringBuilder();
    int cursor = 0;
    for (MarkdownScanner.Span span : MarkdownScanner.protectedSpans(body)) {
      result.append(replace(body.substring(cursor, span.start()), replacement));
      result.append(body, span.start(), span.end());
      cursor = span.end();
    }
    return result.append(replace(body.substring(cursor), replacement)).toString();
  }

  private static String replace(String source, Function<Matcher, String> replacement) {
    Matcher matcher = WIKILINK.matcher(source);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.apply(matcher)));
    return matcher.appendTail(result).toString();
  }

  private static void appendText(List<Map<String, String>> tokens, String value) {
    if (value.isEmpty()) return;
    if (!tokens.isEmpty() && tokens.getLast().get("kind").equals("text")) {
      Map<String, String> previous = tokens.removeLast();
      tokens.add(Map.of("kind", "text", "value", previous.get("value") + value));
    } else {
      tokens.add(Map.of("kind", "text", "value", value));
    }
  }

  private static Map<String, Resolution> index(Collection<Note> notes) {
    Map<String, Resolution> result = new LinkedHashMap<>();
    addIndex(result, notes, note -> withoutMarkdownExtension(note.vaultPath()));
    addIndex(result, notes, Note::publicId);
    addIndex(result, notes, note -> TIMESTAMP.matcher(filenameStem(note)).replaceFirst(""));
    addDescriptiveIndex(result, notes);
    return result;
  }

  private static void addIndex(Map<String, Resolution> index, Collection<Note> notes, Function<Note, String> keys) {
    Map<String, List<PublicLink>> candidates = new LinkedHashMap<>();
    for (Note note : notes) {
      PublicLink link = new PublicLink(note.publicId().strip(), route(note));
      String key = keys.apply(note);
      if (key == null || key.strip().isEmpty() || index.containsKey(key.strip())) continue;
      candidates.computeIfAbsent(key.strip(), ignored -> new ArrayList<>()).add(link);
    }
    candidates.forEach((key, links) -> index.put(key, links.size() == 1 ? new Resolved(links.getFirst()) : new Ambiguous()));
  }

  private static void addDescriptiveIndex(Map<String, Resolution> index, Collection<Note> notes) {
    Map<String, List<PublicLink>> candidates = new LinkedHashMap<>();
    for (Note note : notes) {
      PublicLink link = new PublicLink(note.publicId().strip(), route(note));
      List<String> keys = new ArrayList<>();
      keys.add(stringValue(note.frontmatter().get("title")));
      keys.addAll(note.aliases());
      for (String key : keys) {
        if (key == null || key.strip().isEmpty() || index.containsKey(key.strip())) continue;
        List<PublicLink> values = candidates.computeIfAbsent(key.strip(), ignored -> new ArrayList<>());
        if (values.stream().noneMatch(value -> value.publicId().equals(link.publicId()))) values.add(link);
      }
    }
    candidates.forEach((key, links) -> index.put(key, links.size() == 1 ? new Resolved(links.getFirst()) : new Ambiguous()));
  }

  private static PublicLink resolve(Map<String, Resolution> index, String target) {
    Resolution resolution = index.get(target);
    if (resolution instanceof Ambiguous) throw new ManifestValidationException("ambiguous public link: " + target);
    return resolution instanceof Resolved resolved ? resolved.link() : null;
  }

  private static String label(Matcher match, String target) {
    return match.group(4) == null ? TIMESTAMP.matcher(Path.of(target).getFileName().toString()).replaceFirst("").strip() : match.group(4).strip();
  }

  private static String headingFragment(String heading) {
    if (heading == null) return "";
    String value = heading.substring(1).strip().toLowerCase().replaceAll("[^\\w\\s-]", "").replaceAll("[\\s_-]+", "-").replaceAll("^-|-$", "");
    return value.isEmpty() ? "" : "#" + value;
  }

  private static boolean isAsset(String target) {
    String lowercase = target.toLowerCase();
    return ASSET_EXTENSIONS.stream().anyMatch(lowercase::endsWith);
  }

  private static String withoutMarkdownExtension(String path) { return path.endsWith(".md") ? path.substring(0, path.length() - 3) : path; }
  private static String filenameStem(Note note) { return Path.of(note.vaultPath()).getFileName().toString().replaceFirst("\\.md$", ""); }
  private static String stringValue(Object value) { return value instanceof String text ? text.strip() : ""; }

  private static String route(Note note) {
    if (note.publicCollection().equals("editorial") && note.publicContentType().equals("curated_page")) {
      return note.publicId().equals("home") ? "/ru/" : "/ru/" + note.publicId() + "/";
    }
    return switch (note.publicCollection() + "/" + note.publicContentType()) {
      case "blog/essay" -> "/ru/essays/" + note.publicId() + "/";
      case "blog/claim" -> "/ru/claims/" + note.publicId() + "/";
      case "blog/note" -> "/ru/notes/" + note.publicId() + "/";
      case "music/album" -> "/ru/music/" + note.publicId() + "/";
      case "bibliography/book" -> "/ru/library/" + note.publicId() + "/";
      case "concepts/concept" -> "/ru/concepts/" + note.publicId() + "/";
      default -> throw new IllegalArgumentException("unsupported publication route: " + note.publicCollection() + "/" + note.publicContentType());
    };
  }

  private sealed interface Resolution permits Resolved, Ambiguous { }
  private record Resolved(PublicLink link) implements Resolution { }
  private record Ambiguous() implements Resolution { }
  public record ResolvedLink(String target, String publicId, String route) { }
  public static final class TransclusionException extends RuntimeException { public TransclusionException(String target) { super(target); } }
  public static final class ManifestValidationException extends RuntimeException { public ManifestValidationException(String message) { super(message); } }
}
