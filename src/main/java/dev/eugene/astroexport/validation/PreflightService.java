package dev.eugene.astroexport.validation;

import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
import dev.eugene.astroexport.model.Note;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class PreflightService {
  private final PublicationValidator validator;

  public PreflightService() { this(new PublicationValidator()); }
  public PreflightService(PublicationValidator validator) { this.validator = validator; }

  public Result preflight(Path vault, String notePath) {
    Path relative;
    try {
      relative = Path.of(notePath);
    } catch (RuntimeException exception) {
      return failure("path", notePath + ": must be a vault-relative .md path");
    }
    if (relative.isAbsolute() || !notePath.endsWith(".md") || relative.normalize().startsWith("..")
        || relative.iterator().hasNext() && containsTraversal(relative)) {
      return failure("path", notePath + ": must be a vault-relative .md path");
    }
    Path resolvedVault = vault.toAbsolutePath().normalize();
    Path candidate = resolvedVault.resolve(relative).normalize();
    if (!candidate.startsWith(resolvedVault)) {
      return failure("path", notePath + ": must be a vault-relative .md path");
    }
    try {
      if (!Files.isRegularFile(candidate)) return failure("path", notePath + ": does not exist");
      Path actual = candidate.toRealPath();
      if (!actual.startsWith(resolvedVault.toRealPath())) return failure("path", notePath + ": must be a vault-relative .md path");
      FrontmatterDocument document = FrontmatterDocument.parse(candidate, notePath,
          Files.readString(candidate, StandardCharsets.UTF_8));
      Note note = note(candidate, notePath, document);
      List<PublicationDiagnostic> diagnostics = validator.validate(note).stream()
          .map(item -> new PublicationDiagnostic(item.field(), notePath + ": " + item.message(), item.blocking()))
          .toList();
      return new Result(note, diagnostics);
    } catch (IOException exception) {
      return failure("path", notePath + ": " + exception.getMessage());
    } catch (IllegalArgumentException exception) {
      return failure("frontmatter", notePath + ": invalid frontmatter: " + exception.getMessage());
    }
  }

  private static boolean containsTraversal(Path path) {
    for (Path part : path) if (part.toString().equals("..")) return true;
    return false;
  }

  private static Note note(Path path, String vaultPath, FrontmatterDocument document) {
    Map<String, Object> metadata = document.metadata();
    return new Note(path, vaultPath, path.getFileName().toString().replaceFirst("\\.md$", ""), metadata,
        document.body(), Boolean.TRUE.equals(metadata.get("publish")), text(metadata.get("publicId")),
        text(metadata.get("publicCollection")), text(metadata.get("publicContentType")), List.of());
  }

  private static String text(Object value) { return value instanceof String text ? text.strip() : ""; }
  private static Result failure(String field, String message) { return new Result(null, List.of(new PublicationDiagnostic(field, message))); }

  public record Result(Note note, List<PublicationDiagnostic> diagnostics) {
    public Result { diagnostics = List.copyOf(diagnostics); }
    public boolean ready() { return note != null && diagnostics.stream().noneMatch(PublicationDiagnostic::blocking); }
  }
}
