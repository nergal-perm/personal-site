package dev.eugene.astroexport.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.testsupport.FixtureFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PreflightTest {
  private final PreflightService preflight = new PreflightService();

  @Test
  void reportsAllActiveConceptViolationsWithoutScanningTheVault() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    FixtureFiles.write(vault, "concepts/Organisation.md", "---\n"
        + "publish: true\npublicId: organisation\npublicCollection: concepts\npublicContentType: concept\n"
        + "---\nBody\n");
    FixtureFiles.write(vault, "elsewhere/Invalid.md", "---\npublish: true\n---\n");

    var result = preflight.preflight(vault, "concepts/Organisation.md");

    assertFalse(result.ready());
    assertEquals(List.of(
        new PublicationDiagnostic("description", "concepts/Organisation.md: must be a non-empty string"),
        new PublicationDiagnostic("Определение", "concepts/Organisation.md: must be a non-empty section")),
        result.diagnostics());
  }

  @Test
  void acceptsACompleteVaultRelativeConceptNote() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    FixtureFiles.write(vault, "concepts/Organisation.md", "---\n"
        + "publish: true\npublicId: organisation\npublicCollection: concepts\npublicContentType: concept\n"
        + "description: Public description\n---\n## Определение\n\nVisible.\n");

    var result = preflight.preflight(vault, "concepts/Organisation.md");

    assertTrue(result.ready());
    assertEquals(List.of(), result.diagnostics());
    assertEquals("concepts/Organisation.md", result.note().vaultPath());
  }

  @Test
  void rejectsAbsoluteTraversalAndNonMarkdownPaths() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");

    assertEquals(List.of(new PublicationDiagnostic("path", "../outside.md: must be a vault-relative .md path")),
        preflight.preflight(vault, "../outside.md").diagnostics());
    assertEquals(List.of(new PublicationDiagnostic("path", "/outside.md: must be a vault-relative .md path")),
        preflight.preflight(vault, "/outside.md").diagnostics());
    assertEquals(List.of(new PublicationDiagnostic("path", "blog/Active.txt: must be a vault-relative .md path")),
        preflight.preflight(vault, "blog/Active.txt").diagnostics());
  }

  @Test
  void reportsMissingNoteAsAPathDiagnostic() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");

    assertEquals(List.of(new PublicationDiagnostic("path", "blog/Missing.md: does not exist")),
        preflight.preflight(vault, "blog/Missing.md").diagnostics());
  }
}
