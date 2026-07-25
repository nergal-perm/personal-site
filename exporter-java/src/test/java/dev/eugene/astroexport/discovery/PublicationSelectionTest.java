package dev.eugene.astroexport.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.testsupport.FixtureFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PublicationSelectionTest {
  @Test
  void includesExactPublishTrueYamlBoolean() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    FixtureFiles.write(vault, "notes/Public.md", frontmatter("publish: true\npublicId: public-note\n"
        + "publicCollection: blog\npublicContentType: note"));
    PublicationDiscovery discovery = discoveryReturning("notes/Public.md");

    var result = discovery.select(vault);

    assertEquals(1, result.confirmed());
    assertEquals(List.of("public-note"), result.included().stream().map(note -> note.publicId()).toList());
  }

  @Test
  void ignoresBodyMatchWithoutYamlBoolean() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    FixtureFiles.write(vault, "notes/Example.md", "---\nid: example\n---\n```yaml\npublish: true\n```\n");
    PublicationDiscovery discovery = discoveryReturning("notes/Example.md");

    var result = discovery.select(vault);

    assertEquals(1, result.matched());
    assertEquals(0, result.confirmed());
    assertTrue(result.included().isEmpty());
  }

  @Test
  void recordsConfirmedPublishNotesThatDoNotQualify() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    FixtureFiles.write(vault, "notes/Incomplete.md", frontmatter("publish: true\npublicId: incomplete"));
    PublicationDiscovery discovery = discoveryReturning("notes/Incomplete.md");

    var result = discovery.select(vault);

    assertEquals(1, result.confirmed());
    assertTrue(result.included().isEmpty());
    assertEquals(List.of("notes/Incomplete.md"), result.unqualifiedVaultPaths());
  }

  private static PublicationDiscovery discoveryReturning(String... paths) {
    String stdout = String.join("\u0000", paths) + "\u0000";
    return new PublicationDiscovery((command, workingDirectory) ->
        new PublicationDiscovery.ProcessResult(0, stdout, ""));
  }

  private static String frontmatter(String metadata) {
    return "---\n" + metadata + "\n---\nBody\n";
  }
}
