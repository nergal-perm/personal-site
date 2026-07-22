package dev.eugene.astroexport.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PublicationDiscoveryTest {
  @Test
  void passesTheExplicitVaultDirectoryToRg() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    RecordingProcessRunner runner = new RecordingProcessRunner(
        new PublicationDiscovery.ProcessResult(0, "notes/Public.md\n", ""));

    List<String> candidates = new PublicationDiscovery(runner).findCandidates(vault);

    assertEquals(List.of("notes/Public.md"), candidates);
    assertEquals(
        List.of(
            "rg", "--files-with-matches", "--glob", "*.md", "--glob", "!.*", "--glob", "!**/.*",
            "^publish:[ \\t]+true[ \\t]*$"),
        runner.command);
    assertEquals(vault, runner.workingDirectory);
  }

  @Test
  void skipsHiddenPathsReturnedByRg() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    RecordingProcessRunner runner = new RecordingProcessRunner(
        new PublicationDiscovery.ProcessResult(0, ".private/Hidden.md\nnotes/Public.md\nnotes/.draft.md\n", ""));

    assertEquals(List.of("notes/Public.md"), new PublicationDiscovery(runner).findCandidates(vault));
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
}
