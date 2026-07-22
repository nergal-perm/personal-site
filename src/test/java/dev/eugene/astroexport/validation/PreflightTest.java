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
  void reportsAllActiveConceptViolationsBeforeManifest() throws Exception {
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

  @Test
  void reportsAbsentPublishAsTheOnlyActiveNoteContractDiagnostic() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    makeNote(vault, "blog/Active.md", "id: active\npublicId: active\npublicCollection: blog\npublicContentType: note");

    assertEquals(List.of(new PublicationDiagnostic("publish", "blog/Active.md: must be true; allowed value: true")),
        preflight.preflight(vault, "blog/Active.md").diagnostics());
  }

  @Test
  void convertsMalformedActiveAndPeerFrontmatterToDiagnostics() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    FixtureFiles.write(vault, "blog/Broken.md", "---\npublish: true\ninvalid: [\n---\nBody\n");

    var active = preflight.preflight(vault, "blog/Broken.md");

    assertEquals("frontmatter", active.diagnostics().getFirst().field());
    assertTrue(active.diagnostics().getFirst().message().startsWith("blog/Broken.md: invalid frontmatter: "));

    makeNote(vault, "blog/Active.md", publicNote("active"));
    var withBrokenPeer = preflight.preflight(vault, "blog/Active.md");

    assertTrue(withBrokenPeer.ready());
    assertEquals("frontmatter", withBrokenPeer.workspaceHealth().getFirst().field());
    assertTrue(withBrokenPeer.workspaceHealth().getFirst().message().startsWith("blog/Broken.md: invalid frontmatter: "));
  }

  @Test
  void validActiveNoteIsNotBlockedByAnUnrelatedInvalidPublicPeer() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    makeNote(vault, "blog/Active.md", publicNote("active"));
    makeNote(vault, "blog/Unrelated.md", "id: unrelated\npublish: true\npublicId: unrelated\n"
        + "publicCollection: blog\npublicContentType: case");

    var result = preflight.preflight(vault, "blog/Active.md");

    assertTrue(result.ready());
    assertTrue(result.entry().isPresent());
    assertEquals(List.of(), result.diagnostics());
    assertEquals(List.of(new PublicationDiagnostic("publicContentType",
        "blog/Unrelated.md: must be one of: claim, essay, note")), result.workspaceHealth());
  }

  @Test
  void reportsManifestValidationErrorsForUnsupportedActiveTopics() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    makeNote(vault, "blog/Active.md", publicNote("active") + "\ntopics:\n  - unsupported-topic");
    makeNote(vault, "blog/Peer.md", publicNote("peer"));

    var result = preflight.preflight(vault, "blog/Active.md");

    assertFalse(result.ready());
    assertTrue(result.entry().isEmpty());
    assertEquals(List.of(new PublicationDiagnostic("topics",
        "blog/Active.md: contains unsupported values: unsupported-topic")), result.diagnostics());
  }

  @Test
  void convertsWikilinksToSelectedPublicPeerRoutesAndRejectsPrivateTransclusions() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    makeNote(vault, "blog/Active.md", publicNote("active"), "Read [[Peer Note]].");
    makeNote(vault, "blog/Peer Note.md", publicNote("peer"));

    var linked = preflight.preflight(vault, "blog/Active.md");

    assertTrue(linked.ready());
    assertEquals("Read [Peer Note](/ru/notes/peer/).", linked.entry().orElseThrow().body());

    makeNote(vault, "blog/Transclusion.md", publicNote("transclusion"), "![[private/Source]]");
    makeNote(vault, "private/Source.md", "id: private");
    var transclusion = preflight.preflight(vault, "blog/Transclusion.md");

    assertFalse(transclusion.ready());
    assertEquals(List.of(new PublicationDiagnostic("transclusion",
        "blog/Transclusion.md: unpublished transclusion private/Source")), transclusion.diagnostics());
  }

  @Test
  void ignoresLinksAndTransclusionsInsideProtectedMarkdownContexts() throws Exception {
    for (String body : List.of(
        "```markdown\n![[private/Source]] [[Peer]]\n```\n",
        "`![[private/Source]] [[Peer]]`\n",
        "<pre>\n![[private/Source]] [[Peer]]\n</pre>\n",
        "<!-- ![[private/Source]] [[Peer]] -->\n",
        "%% ![[private/Source]] [[Peer]] %%\n")) {
      Path vault = Files.createTempDirectory("astro-export-vault");
      makeNote(vault, "blog/Active.md", publicNote("active"), body);
      makeNote(vault, "private/Source.md", "id: private");

      var result = preflight.preflight(vault, "blog/Active.md");

      assertTrue(result.ready(), body);
      assertEquals(body, result.entry().orElseThrow().body(), body);
    }
  }

  @Test
  void ignoresEscapedTransclusionsAndPreservesPublicHeadingFragments() throws Exception {
    Path escapedVault = Files.createTempDirectory("astro-export-vault");
    String escaped = "\\![[private/Source]]\n";
    makeNote(escapedVault, "blog/Active.md", publicNote("active"), escaped);
    makeNote(escapedVault, "private/Source.md", "id: private");

    var escapedResult = preflight.preflight(escapedVault, "blog/Active.md");

    assertTrue(escapedResult.ready());
    assertEquals(escaped, escapedResult.entry().orElseThrow().body());

    Path fragmentVault = Files.createTempDirectory("astro-export-vault");
    makeNote(fragmentVault, "blog/Active.md", publicNote("active"), "Read [[Peer#A section]].");
    makeNote(fragmentVault, "blog/Peer.md", publicNote("peer"));

    var fragmentResult = preflight.preflight(fragmentVault, "blog/Active.md");

    assertTrue(fragmentResult.ready());
    assertEquals("Read [Peer](/ru/notes/peer/#a-section).", fragmentResult.entry().orElseThrow().body());
  }

  @Test
  void resolvesUniqueAliasesAndRejectsAmbiguousDescriptiveTargets() throws Exception {
    Path uniqueVault = Files.createTempDirectory("astro-export-vault");
    makeNote(uniqueVault, "blog/Active.md", publicNote("active"), "Read [[Alternate]].");
    makeNote(uniqueVault, "blog/Peer.md", publicNote("peer") + "\naliases:\n  - Alternate");

    var unique = preflight.preflight(uniqueVault, "blog/Active.md");

    assertTrue(unique.ready());
    assertEquals("Read [Alternate](/ru/notes/peer/).", unique.entry().orElseThrow().body());

    Path ambiguousVault = Files.createTempDirectory("astro-export-vault");
    makeNote(ambiguousVault, "blog/Active.md", publicNote("active"), "Read [[Shared]].");
    makeNote(ambiguousVault, "blog/One.md", publicNote("one") + "\naliases:\n  - Shared");
    makeNote(ambiguousVault, "blog/Two.md", publicNote("two") + "\naliases:\n  - Shared");

    var ambiguous = preflight.preflight(ambiguousVault, "blog/Active.md");

    assertFalse(ambiguous.ready());
    assertEquals(List.of(new PublicationDiagnostic("link", "blog/Active.md: public link Shared is ambiguous")),
        ambiguous.diagnostics());
  }

  @Test
  void requiresExactPublishTrueLineForSelectorInclusion() throws Exception {
    for (String publishLine : List.of("publish: True", "publish: true # comment")) {
      Path vault = Files.createTempDirectory("astro-export-vault");
      makeNote(vault, "blog/Active.md", "id: active\n" + publishLine + "\n"
          + "publicId: active\npublicCollection: blog\npublicContentType: note");

      var result = preflight.preflight(vault, "blog/Active.md");

      assertFalse(result.ready());
      assertTrue(result.entry().isEmpty());
      assertEquals(List.of(new PublicationDiagnostic("selection",
          "blog/Active.md: must be selected for publication")), result.diagnostics());
    }
  }

  @Test
  void resolvesClaimRelationsAndEditorialShowcaseReferencesAgainstSelectedPeers() throws Exception {
    Path vault = Files.createTempDirectory("astro-export-vault");
    makeNote(vault, "claims/Active Claim.md", publicNote("active-claim", "claim")
        + "\nstatement: Active claim.\nsupports:\n  - \"[[Peer Claim]]\"\nrefines:\n  - \"[[peer-claim]]\"");
    makeNote(vault, "claims/Peer Claim.md", publicNote("peer-claim", "claim") + "\nstatement: Peer claim.");

    var claim = preflight.preflight(vault, "claims/Active Claim.md");

    assertTrue(claim.ready());
    assertEquals(List.of(java.util.Map.of("label", "Peer Claim", "target", "peer-claim")),
        claim.entry().orElseThrow().metadata().get("supports"));
    assertEquals(List.of(java.util.Map.of("label", "peer-claim", "target", "peer-claim")),
        claim.entry().orElseThrow().metadata().get("refines"));

    makeNote(vault, "blog/editorial/essays.md", "id: essays\npublish: true\npublicId: essays\n"
        + "publicCollection: editorial\npublicContentType: curated_page\neditorialPage: essays", """
        ## Кратко

        Эссе.

        ## Eyebrow

        Тексты

        ## Принцип списка

        Полный список.

        Подсказка поиска:: Искать

        ## Витрина

        ### [[Peer Essay]]

        Начать с [[Peer Essay]].
        """);
    makeNote(vault, "blog/Peer Essay.md", publicNote("peer-essay", "essay"));

    var editorial = preflight.preflight(vault, "blog/editorial/essays.md");

    assertTrue(editorial.ready());
    assertEquals(List.of("peer-essay"), editorial.entry().orElseThrow().metadata().get("pinned"));
    assertEquals(List.of(java.util.Map.of("target", "peer-essay", "text", List.of(
        java.util.Map.of("kind", "text", "value", "Начать с "),
        java.util.Map.of("kind", "reference", "target", "peer-essay"),
        java.util.Map.of("kind", "text", "value", ".")))),
        editorial.entry().orElseThrow().metadata().get("showcase"));
  }

  private static void makeNote(Path vault, String name, String frontmatter) throws Exception {
    makeNote(vault, name, frontmatter, "Body");
  }

  private static void makeNote(Path vault, String name, String frontmatter, String body) throws Exception {
    FixtureFiles.write(vault, name, "---\n" + frontmatter + "\n---\n" + body);
  }

  private static String publicNote(String publicId) {
    return publicNote(publicId, "note");
  }

  private static String publicNote(String publicId, String contentType) {
    return "id: " + publicId + "\npublish: true\npublicId: " + publicId + "\n"
        + "publicCollection: blog\npublicContentType: " + contentType;
  }
}
