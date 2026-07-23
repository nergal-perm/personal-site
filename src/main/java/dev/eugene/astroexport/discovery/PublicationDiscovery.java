package dev.eugene.astroexport.discovery;

import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.SelectionResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class PublicationDiscovery {
  private static final List<String> RG_COMMAND = List.of(
      "rg", "--files-with-matches", "--null", "--no-ignore", "--glob", "*.md",
      "^publish:[ \\t]+true[ \\t]*$", ".");

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
      throw new PublicationSearchException(result.stderr().isBlank()
          ? "rg failed with exit code " + result.exitCode()
          : result.stderr().strip());
    }

    return Arrays.stream(result.stdout().split("\u0000"))
        .filter(path -> !path.isEmpty())
        .map(path -> path.startsWith("./") ? path.substring(2) : path)
        .sorted()
        .toList();
  }

  public SelectionResult select(Path vault) {
    List<String> candidates = findCandidates(vault);
    List<Note> included = new ArrayList<>();
    List<SelectionResult.Exclusion> excluded = new ArrayList<>();
    int confirmed = 0;

    for (String vaultPath : candidates) {
      Path path = vault.resolve(vaultPath);
      FrontmatterDocument document;
      try {
        document = FrontmatterDocument.parse(path, vaultPath, Files.readString(path, StandardCharsets.UTF_8));
      } catch (IOException exception) {
        throw new PublicationSearchException("could not read " + vaultPath, exception);
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
        excluded.add(new SelectionResult.Exclusion(path, missingPublicationField(publicId, publicCollection, publicContentType), vaultPath));
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
    return new SelectionResult(List.copyOf(included), List.copyOf(excluded), candidates.size(), confirmed);
  }

  private static String missingPublicationField(
      String publicId,
      String publicCollection,
      String publicContentType) {
    if (publicId.isEmpty()) {
      return "missing publicId";
    }
    if (publicCollection.isEmpty()) {
      return "missing publicCollection";
    }
    return "missing publicContentType";
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
      return drainProcess(process);
    } catch (IOException exception) {
      throw new PublicationSearchException("ripgrep (`rg`) is required for publication discovery", exception);
    }
  }

  static ProcessResult drainProcess(Process process) {
    StreamDrainer stdout = new StreamDrainer(process.getInputStream());
    StreamDrainer stderr = new StreamDrainer(process.getErrorStream());
    stdout.start();
    stderr.start();
    try {
      int exitCode = process.waitFor();
      stdout.await();
      stderr.await();
      return new ProcessResult(exitCode, stdout.content(), stderr.content());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new PublicationSearchException("rg interrupted", exception);
    } catch (IOException exception) {
      throw new PublicationSearchException("could not read rg output", exception);
    }
  }

  private static final class StreamDrainer {
    private final InputStream stream;
    private Thread thread;
    private byte[] bytes;
    private IOException failure;

    private StreamDrainer(InputStream stream) {
      this.stream = stream;
    }

    private void start() {
      thread = Thread.ofVirtual().start(() -> {
        try (stream) {
          bytes = stream.readAllBytes();
        } catch (IOException exception) {
          failure = exception;
        }
      });
    }

    private void await() throws InterruptedException {
      thread.join();
    }

    private String content() throws IOException {
      if (failure != null) {
        throw failure;
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  @FunctionalInterface
  public interface ProcessRunner {
    ProcessResult run(List<String> command, Path workingDirectory);
  }

  public record ProcessResult(int exitCode, String stdout, String stderr) {
  }

  public static final class PublicationSearchException extends RuntimeException {
    public PublicationSearchException(String message) {
      super(message);
    }

    public PublicationSearchException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
