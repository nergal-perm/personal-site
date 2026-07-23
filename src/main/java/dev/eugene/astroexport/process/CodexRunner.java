package dev.eugene.astroexport.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Executes a bounded command without a shell and captures its result. */
public final class CodexRunner {
  public Run run(Path workdir, String prompt, Duration timeout)
      throws IOException, InterruptedException {
    Path resolved = resolvedDirectory(workdir);
    return runResolved(resolved, defaultCommandForResolved(resolved, prompt), timeout);
  }

  public Run run(Path workdir, List<String> args, Duration timeout)
      throws IOException, InterruptedException {
    return runResolved(resolvedDirectory(workdir), args, timeout);
  }

  static List<String> defaultCommand(Path workdir, String prompt) throws IOException {
    return defaultCommandForResolved(resolvedDirectory(workdir), prompt);
  }

  private Run runResolved(Path resolved, List<String> args, Duration timeout)
      throws IOException, InterruptedException {
    if (args.isEmpty()) {
      throw new IllegalArgumentException("Codex command must not be empty");
    }
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("Codex timeout must be positive");
    }
    Process process = new ProcessBuilder(List.copyOf(args))
        .directory(resolved.toFile())
        .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
        .start();
    CompletableFuture<String> stdout = read(process.getInputStream());
    CompletableFuture<String> stderr = read(process.getErrorStream());
    boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!completed) {
      process.destroyForcibly();
      process.waitFor();
      return new Run(-1, stdout.join(), stderr.join(), true);
    }
    return new Run(process.exitValue(), stdout.join(), stderr.join(), false);
  }

  private static Path resolvedDirectory(Path workdir) throws IOException {
    if (!Files.isDirectory(workdir)) {
      throw new IllegalArgumentException("Codex job directory must be a directory");
    }
    return workdir.toRealPath();
  }

  private static List<String> defaultCommandForResolved(Path resolved, String prompt) {
    return List.of(
        "codex",
        "exec",
        "--ephemeral",
        "--sandbox",
        "workspace-write",
        "--skip-git-repo-check",
        "-C",
        resolved.toString(),
        "--output-last-message",
        resolved.resolve("agent-message.txt").toString(),
        prompt);
  }

  private static CompletableFuture<String> read(java.io.InputStream stream) {
    return CompletableFuture.supplyAsync(() -> {
      try (stream) {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException error) {
        throw new java.io.UncheckedIOException(error);
      }
    });
  }

  public record Run(int exitCode, String stdout, String stderr, boolean timedOut) { }
}
