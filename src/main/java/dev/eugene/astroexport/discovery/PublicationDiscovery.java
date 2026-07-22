package dev.eugene.astroexport.discovery;

import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.SelectionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PublicationDiscovery {
  private static final List<String> RG_COMMAND = List.of(
      "rg", "--files-with-matches", "--glob", "*.md", "--glob", "!.*", "--glob", "!**/.*",
      "^publish:[ \\t]+true[ \\t]*$");

  private final ProcessRunner processRunner;

  public PublicationDiscovery() {
    this(PublicationDiscovery::runProcess);
  }

  public PublicationDiscovery(ProcessRunner processRunner) {
    this.processRunner = processRunner;
  }

  public List<String> findCandidates(Path vault) {
    ProcessResult result = processRunner.run(RG_COMMAND, vault);
    if (result.exitCode() == 1) {
      return List.of();
    }
    if (result.exitCode() != 0) {
      throw new IllegalStateException(result.stderr().isBlank()
          ? "rg failed with exit code " + result.exitCode()
          : result.stderr().strip());
    }

    return result.stdout().lines()
        .map(path -> path.replace('\\', '/'))
        .filter(path -> !path.isBlank())
        .filter(path -> !isHiddenPath(path))
        .toList();
  }

  public SelectionResult select(Path vault) {
    List<String> candidates = findCandidates(vault);
    List<Note> included = new ArrayList<>();
    List<String> unqualified = new ArrayList<>();
    int confirmed = 0;

    for (String vaultPath : candidates) {
      Path path = vault.resolve(vaultPath);
      FrontmatterDocument document;
      try {
        document = FrontmatterDocument.parse(path, vaultPath, Files.readString(path, StandardCharsets.UTF_8));
      } catch (IOException exception) {
        throw new IllegalStateException("could not read " + vaultPath, exception);
      }

      Map<String, Object> metadata = document.metadata();
      if (!Boolean.TRUE.equals(metadata.get("publish"))) {
        continue;
      }
      confirmed++;

      String publicId = stringValue(metadata.get("publicId"));
      String publicCollection = stringValue(metadata.get("publicCollection"));
      String publicContentType = stringValue(metadata.get("publicContentType"));
      if (publicId.isEmpty() || publicCollection.isEmpty() || publicContentType.isEmpty()) {
        unqualified.add(vaultPath);
        continue;
      }

      included.add(new Note(
          path,
          vaultPath,
          path.getFileName().toString().replaceFirst("\\.md$", ""),
          metadata,
          document.body(),
          true,
          publicId,
          publicCollection,
          publicContentType,
          aliases(metadata.get("aliases"))));
    }
    return new SelectionResult(List.copyOf(included), List.copyOf(unqualified), candidates.size(), confirmed);
  }

  private static boolean isHiddenPath(String path) {
    for (String part : path.split("/")) {
      if (part.startsWith(".")) {
        return true;
      }
    }
    return false;
  }

  private static String stringValue(Object value) {
    return value instanceof String text ? text.strip() : "";
  }

  private static List<String> aliases(Object value) {
    if (value instanceof String alias && !alias.isBlank()) {
      return List.of(alias);
    }
    if (!(value instanceof List<?> values)) {
      return List.of();
    }
    return values.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .filter(alias -> !alias.isBlank())
        .toList();
  }

  private static ProcessResult runProcess(List<String> command, Path workingDirectory) {
    try {
      Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).start();
      int exitCode = process.waitFor();
      return new ProcessResult(
          exitCode,
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
          new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new IllegalStateException("ripgrep (`rg`) is required for publication discovery", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("rg interrupted", exception);
    }
  }

  @FunctionalInterface
  public interface ProcessRunner {
    ProcessResult run(List<String> command, Path workingDirectory);
  }

  public record ProcessResult(int exitCode, String stdout, String stderr) {
  }
}
