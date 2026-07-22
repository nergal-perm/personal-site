package dev.eugene.astroexport.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class PublicationDiscoveryTest {
  @Test
  void passesTheExplicitVaultDirectoryToRg() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    RecordingProcessRunner runner = new RecordingProcessRunner(
        new PublicationDiscovery.ProcessResult(0, "notes/Public.md\u0000", ""));

    List<String> candidates = new PublicationDiscovery(runner).findCandidates(vault);

    assertEquals(List.of("notes/Public.md"), candidates);
    assertEquals(
        List.of(
            "rg", "--files-with-matches", "--null", "--no-ignore", "--glob", "*.md",
            "^publish:[ \\t]+true[ \\t]*$", "."),
        runner.command);
    assertEquals(vault, runner.workingDirectory);
  }

  @Test
  void parsesNulSeparatedRgOutput() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    RecordingProcessRunner runner = new RecordingProcessRunner(
        new PublicationDiscovery.ProcessResult(0, "notes/Second.md\u0000notes/First.md\u0000", ""));

    assertEquals(
        List.of("notes/First.md", "notes/Second.md"),
        new PublicationDiscovery(runner).findCandidates(vault));
  }

  @Test
  void preservesLiteralBackslashesInRawPaths() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    String rawPath = "notes\\literal-backslash.md";
    RecordingProcessRunner runner = new RecordingProcessRunner(
        new PublicationDiscovery.ProcessResult(0, rawPath + "\u0000", ""));

    assertEquals(List.of(rawPath), new PublicationDiscovery(runner).findCandidates(vault));
  }

  @Test
  void retainsWhitespaceOnlyRawPathSegments() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    RecordingProcessRunner runner = new RecordingProcessRunner(
        new PublicationDiscovery.ProcessResult(0, "   \u0000", ""));

    assertEquals(List.of("   "), new PublicationDiscovery(runner).findCandidates(vault));
  }

  @Test
  void drainsBothProcessStreamsBeforeWaitingForExit() {
    BlockingProcess process = new BlockingProcess("stdout", "stderr");

    PublicationDiscovery.ProcessResult result = PublicationDiscovery.drainProcess(process);

    assertEquals(0, result.exitCode());
    assertEquals("stdout", result.stdout());
    assertEquals("stderr", result.stderr());
  }

  private static final class RecordingProcessRunner implements PublicationDiscovery.ProcessRunner {
    private final PublicationDiscovery.ProcessResult result;
    private List<String> command;
    private Path workingDirectory;

    private RecordingProcessRunner(PublicationDiscovery.ProcessResult result) {
      this.result = result;
    }

    @Override
    public PublicationDiscovery.ProcessResult run(List<String> command, Path workingDirectory) {
      this.command = command;
      this.workingDirectory = workingDirectory;
      return result;
    }
  }

  private static final class BlockingProcess extends Process {
    private final CountDownLatch streamsDrained = new CountDownLatch(2);
    private final InputStream stdout;
    private final InputStream stderr;

    private BlockingProcess(String stdout, String stderr) {
      this.stdout = drainingStream(stdout);
      this.stderr = drainingStream(stderr);
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return stdout;
    }

    @Override
    public InputStream getErrorStream() {
      return stderr;
    }

    @Override
    public int waitFor() throws InterruptedException {
      if (!streamsDrained.await(1, TimeUnit.SECONDS)) {
        throw new AssertionError("waitFor called before stdout and stderr were drained");
      }
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {
    }

    private InputStream drainingStream(String content) {
      return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)) {
        @Override
        public byte[] readAllBytes() {
          byte[] bytes = super.readAllBytes();
          streamsDrained.countDown();
          return bytes;
        }
      };
    }
  }
}
