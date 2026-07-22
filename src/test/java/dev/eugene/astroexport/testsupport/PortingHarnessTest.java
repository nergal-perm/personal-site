package dev.eugene.astroexport.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PortingHarnessTest {
  @Test
  void fixtureFilesRoundTripUtf8Content() throws Exception {
    Path root = Files.createTempDirectory("astro-export-fixture");

    FixtureFiles.write(root, "nested/note.md", "Привет");

    assertEquals("Привет", FixtureFiles.read(root, "nested/note.md"));
  }

  @Test
  void commandFixtureCapturesHelpOutput() {
    CommandFixture.Result result = new CommandFixture().run("--help");

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("Usage:"));
  }

  @Test
  void goldenAssertionsAcceptAnEmptyTree() throws Exception {
    Path root = Files.createTempDirectory("astro-export-golden");

    GoldenAssertions.assertTreeHash(
        root, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }
}
