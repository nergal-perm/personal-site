package dev.eugene.astroexport.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.testsupport.FixtureFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Disabled;
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

  @Test
  void rejectsPaddedEditorialPageBeforeAnySelectionWork() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    FixtureFiles.write(vault, "blog/editorial/home.md", "---\n"
        + "publish: true\npublicId: home\npublicCollection: editorial\npublicContentType: curated_page\n"
        + "editorialPage: ' home '\n---\nBody\n");

    assertEquals(List.of(new PublicationDiagnostic("editorialPage", "blog/editorial/home.md: must be one of: "
        + "about, claims, concepts, essays, home, library, music, notes, now")),
        preflight.preflight(vault, "blog/editorial/home.md").diagnostics());
  }

  @Test
  void reportsAllowedBooleanValueForASelectedNoteThatIsNotPublished() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    FixtureFiles.write(vault, "blog/Active.md", "---\n"
        + "publish: false\npublicId: active\npublicCollection: blog\npublicContentType: note\n---\nBody\n");

    assertEquals(List.of(new PublicationDiagnostic("publish", "blog/Active.md: must be true; allowed value: true")),
        preflight.preflight(vault, "blog/Active.md").diagnostics());
  }

  @Disabled("Pending manifest and selected-peer model from later tasks")
  @Test
  void reportsWorkspaceHealthForAnUnrelatedInvalidPublicPeer() {
    // Port of Python test_preflight_valid_active_note_is_not_blocked_by_unrelated_invalid_public_note.
  }

  @Disabled("Pending manifest builder from later tasks")
  @Test
  void reportsManifestValidationErrorsForTheActiveNote() {
    // Port of Python test_preflight_reports_manifest_error_on_active_note_not_a_valid_peer.
  }

  @Disabled("Pending manifest link rendering and transclusion support from later tasks")
  @Test
  void resolvesSelectedPeerLinksAndReportsUnpublishedTransclusions() {
    // Ports Python peer-wikilink and unpublished-transclusion preflight tests.
  }

  @Disabled("Pending selection integration; Task 4 confinement intentionally does not scan the vault")
  @Test
  void requiresTheActiveNoteToBeSelectedBeforeManifestGeneration() {
    // Port of Python test_preflight_requires_active_note_to_be_in_selector_included.
  }

  @Disabled("Pending manifest relation and editorial showcase support from later tasks")
  @Test
  void resolvesClaimRelationsAndEditorialShowcaseReferencesAgainstSelectedPeers() {
    // Ports the final two Python preflight tests.
  }
}
