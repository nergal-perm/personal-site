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
  void reportsAllActiveConceptViolationsInSchemaOrder() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    makeNote(vault, "concepts/Organisation.md", "publish: true\npublicId: organisation\n"
        + "publicCollection: concepts\npublicContentType: concept", "Body");

    var result = preflight.preflight(vault, "concepts/Organisation.md");

    assertFalse(result.ready());
    assertEquals(List.of(
        new PublicationDiagnostic("description", "concepts/Organisation.md: must be a non-empty string"),
        new PublicationDiagnostic("Определение", "concepts/Organisation.md: must be a non-empty section")),
        result.diagnostics());
  }

  @Test
  void acceptsCompleteActiveNoteWithoutReadingUnrelatedMalformedMarkdown() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    makeNote(vault, "concepts/Organisation.md", "publish: true\npublicId: organisation\n"
        + "publicCollection: concepts\npublicContentType: concept\ndescription: Public description",
        "## Определение\n\nVisible.\n");
    FixtureFiles.write(vault, "elsewhere/Broken.md", "---\npublish: true\ninvalid: [\n---\nBody\n");

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
  void reportsMissingAndMalformedActiveNotesWithoutThrowing() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    assertEquals(List.of(new PublicationDiagnostic("path", "blog/Missing.md: does not exist")),
        preflight.preflight(vault, "blog/Missing.md").diagnostics());
    FixtureFiles.write(vault, "blog/Broken.md", "---\npublish: true\ninvalid: [\n---\nBody\n");
    var malformed = preflight.preflight(vault, "blog/Broken.md");
    assertEquals("frontmatter", malformed.diagnostics().getFirst().field());
    assertTrue(malformed.diagnostics().getFirst().message().startsWith("blog/Broken.md: invalid frontmatter: "));
  }

  @Test
  void reportsPublishFalseOrAbsentAndPaddedEditorialPage() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    makeNote(vault, "blog/False.md", "publish: false\npublicId: active\npublicCollection: blog\npublicContentType: note", "Body");
    makeNote(vault, "blog/Absent.md", "publicId: active\npublicCollection: blog\npublicContentType: note", "Body");
    makeNote(vault, "blog/Home.md", "publish: true\npublicId: home\npublicCollection: editorial\n"
        + "publicContentType: curated_page\neditorialPage: ' home '", "Body");

    assertEquals(List.of(new PublicationDiagnostic("publish", "blog/False.md: must be true; allowed value: true")),
        preflight.preflight(vault, "blog/False.md").diagnostics());
    assertEquals(List.of(new PublicationDiagnostic("publish", "blog/Absent.md: must be true; allowed value: true")),
        preflight.preflight(vault, "blog/Absent.md").diagnostics());
    assertEquals(List.of(new PublicationDiagnostic("editorialPage", "blog/Home.md: must be one of: "
        + "about, claims, concepts, essays, home, library, music, notes, now")),
        preflight.preflight(vault, "blog/Home.md").diagnostics());
  }

  private static void makeNote(Path vault, String name, String frontmatter, String body) throws Exception {
    FixtureFiles.write(vault, name, "---\n" + frontmatter + "\n---\n" + body);
  }
}
