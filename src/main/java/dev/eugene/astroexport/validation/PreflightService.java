package dev.eugene.astroexport.validation;

import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
import dev.eugene.astroexport.model.Note;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class PreflightService {
  private static final Pattern PUBLISH_TRUE_LINE = Pattern.compile("(?m)^publish:[ \\t]+true[ \\t]*$");
  private static final Pattern TRUE_LIKE_PUBLISH_DECLARATION =
      Pattern.compile("(?im)^publish:[ \\t]+true(?:[ \\t]+#.*)?[ \\t]*$");
  private final PublicationValidator validator;

  public PreflightService() { this(new PublicationValidator()); }
  public PreflightService(PublicationValidator validator) { this.validator = validator; }

  public Result preflight(Path vault, String notePath) {
    Loaded loaded = load(vault, notePath);
    if (loaded.error() != null) return failure(loaded.error());
    Note note = loaded.note();
    if (!loaded.selectorIncluded() && loaded.declaresTrueLikePublish()) {
      return new Result(note, List.of(new PublicationDiagnostic("selection",
          note.vaultPath() + ": must be selected for publication")));
    }
    List<PublicationDiagnostic> diagnostics = validator.validate(note).stream()
        .map(item -> new PublicationDiagnostic(item.field(), note.vaultPath() + ": " + item.message(), item.blocking()))
        .toList();
    return new Result(note, diagnostics);
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
      Path realVault = resolvedVault.toRealPath();
      Path realCandidate = candidate.toRealPath();
      if (!realCandidate.startsWith(realVault)) return Loaded.error("path", notePath + ": must be a vault-relative .md path");
      String source = Files.readString(realCandidate, StandardCharsets.UTF_8);
      return Loaded.note(note(realCandidate, notePath, FrontmatterDocument.parse(realCandidate, notePath, source)),
          PUBLISH_TRUE_LINE.matcher(source).find(), TRUE_LIKE_PUBLISH_DECLARATION.matcher(source).find());
    } catch (IOException exception) { return Loaded.error("path", notePath + ": " + exception.getMessage());
    } catch (RuntimeException exception) { return Loaded.error("frontmatter", notePath + ": invalid frontmatter: " + exception.getMessage()); }
  }

  private static Note note(Path path, String vaultPath, FrontmatterDocument document) {
    Map<String, Object> metadata = new LinkedHashMap<>(document.metadata());
    return new Note(path, vaultPath, path.getFileName().toString().replaceFirst("\\.md$", ""), metadata,
        document.body(), Boolean.TRUE.equals(metadata.get("publish")), text(metadata.get("publicId")),
        text(metadata.get("publicCollection")), text(metadata.get("publicContentType")), List.of());
  }

  private static String text(Object value) { return value instanceof String text ? text.strip() : ""; }
  private static boolean containsTraversal(Path path) { for (Path part : path) if (part.toString().equals("..")) return true; return false; }
  private static Result failure(Error error) { return new Result(null, List.of(new PublicationDiagnostic(error.field(), error.message()))); }

  public record Result(Note note, List<PublicationDiagnostic> diagnostics) {
    public Result { diagnostics = List.copyOf(diagnostics); }
    public boolean ready() { return note != null && diagnostics.stream().noneMatch(PublicationDiagnostic::blocking); }
  }
  private record Error(String field, String message) { }
  private record Loaded(Note note, Error error, boolean selectorIncluded, boolean declaresTrueLikePublish) {
    static Loaded note(Note note, boolean selectorIncluded, boolean declaresTrueLikePublish) {
      return new Loaded(note, null, selectorIncluded, declaresTrueLikePublish);
    }
    static Loaded error(String field, String message) {
      return new Loaded(null, new Error(field, message), false, false);
    }
  }
}
