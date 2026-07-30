package dev.eugene.astroexport.references;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eugene.astroexport.validation.PublicationDiagnostic;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SemanticReferencePlannerTest {

  @Test
  void replacesWikiLinksWithStableReferenceIds() {
    SemanticReferencePlanner planner = new SemanticReferencePlanner();
    VaultReferenceResolver resolver = resolver(
        entry("vault-ref-0001", "notes/One.md", "id-one", "One", List.of()),
        entry("vault-ref-0002", "notes/Two.md", "id-two", "Two", List.of()));

    SemanticReferencePlanner.PreparedSemanticBody prepared = planner.prepare(
        "blog/Source.md",
        "vault-ref-page",
        "[[One]] and [[id-two|two]]",
        Optional.empty(),
        resolver);

    assertEquals("[One](ref:ref-0001) and [two](ref:ref-0002)", prepared.markdown());
    assertEquals(List.of("ref-0001", "ref-0002"), prepared.plan().order());
    assertEquals(Map.of(
            "ref-0001", new PageReferenceMap.Reference("vault-ref-0001", "One", "", "One"),
            "ref-0002", new PageReferenceMap.Reference("vault-ref-0002", "id-two", "", "two")),
        prepared.plan().references());
    assertEquals(List.of(), prepared.diagnostics());
  }

  @Test
  void emitsOriginalLabelForUnresolvedTargetsAndAddsDiagnostic() {
    SemanticReferencePlanner planner = new SemanticReferencePlanner();
    VaultReferenceResolver resolver = resolver(
        entry("vault-ref-0001", "notes/One.md", "id-one", "One", List.of()));

    SemanticReferencePlanner.PreparedSemanticBody prepared = planner.prepare(
        "blog/Source.md",
        "vault-ref-page",
        "See [[Missing|the missing note]].",
        Optional.empty(),
        resolver);

    assertEquals("See the missing note.", prepared.markdown());
    assertEquals(1, prepared.diagnostics().size());
    PublicationDiagnostic diagnostic = prepared.diagnostics().getFirst();
    assertEquals("unresolved-reference", diagnostic.field());
    assertEquals(false, diagnostic.blocking());
  }

  @Test
  void throwsForAmbiguousTargets() {
    SemanticReferencePlanner planner = new SemanticReferencePlanner();
    VaultReferenceResolver resolver = resolver(
        entry("vault-ref-0001", "notes/One.md", null, "Shared", List.of("Shared")),
        entry("vault-ref-0002", "notes/Two.md", null, "Shared", List.of("Shared")));

    assertEquals(
        "ambiguous-reference-target",
        assertThrows(
            ReferencePlanningException.class,
            () -> planner.prepare(
                "blog/Source.md",
                "vault-ref-page",
                "[[Shared]].",
                Optional.empty(),
                resolver)).code());
  }

  @Test
  void preservesTransclusionsAndDoesNotEmitSemanticReferences() {
    SemanticReferencePlanner planner = new SemanticReferencePlanner();
    VaultReferenceResolver resolver = resolver(
        entry("vault-ref-0001", "notes/One.md", "id-one", "One", List.of()));

    SemanticReferencePlanner.PreparedSemanticBody prepared = planner.prepare(
        "blog/Source.md",
        "vault-ref-page",
        "![[One]] and ![[/assets/cover.png]].",
        Optional.empty(),
        resolver);

    assertEquals("![[One]] and ![[/assets/cover.png]].", prepared.markdown());
    assertEquals(List.of(), prepared.plan().order());
    assertEquals(List.of(), prepared.diagnostics());
  }

  @Test
  void reusesPreviousIdentifiersWherePossibleAndFlagsAmbiguousHistory() {
    SemanticReferencePlanner planner = new SemanticReferencePlanner();
    VaultReferenceResolver resolver = resolver(
        entry("vault-ref-0001", "notes/One.md", "id-one", "One", List.of()));

    SemanticReferencePlanner.PreparedSemanticBody first = planner.prepare(
        "blog/Source.md",
        "vault-ref-page",
        "[[One|First]] and [[One|Second]]",
        Optional.empty(),
        resolver);

    assertEquals(
        "[First](ref:ref-0001) and [Second](ref:ref-0002)",
        first.markdown());
    assertEquals(List.of("ref-0001", "ref-0002"), first.plan().order());

    SemanticReferencePlanner.PreparedSemanticBody second = planner.prepare(
        "blog/Source.md",
        "vault-ref-page",
        "[[One|First]] and [[One|Second]]",
        Optional.of(PageReferenceMap.bind(first.plan(), bytes("ru"), bytes("en"))),
        resolver);

    assertEquals(
        "[First](ref:ref-0001) and [Second](ref:ref-0002)",
        second.markdown());
    assertEquals(List.of(), second.diagnostics());

    SemanticReferencePlanner.PreparedSemanticBody ambiguous = planner.prepare(
        "blog/Source.md",
        "vault-ref-page",
        "[[One]]",
        Optional.of(new PageReferenceMap(
            PageReferenceMap.SCHEMA_VERSION,
            "vault-ref-page",
            "blog/Source.md",
            "ru",
            "en",
            List.of("ref-0001", "ref-0010", "ref-0011"),
            Map.of(
                "ref-0001", new PageReferenceMap.Reference("vault-ref-0001", "Other", "", "One"),
                "ref-0010", new PageReferenceMap.Reference("vault-ref-0001", "One", "", "One"),
                "ref-0011", new PageReferenceMap.Reference("vault-ref-0001", "One", "", "One")))),
        resolver);

    assertEquals("[One](ref:ref-0012)", ambiguous.markdown());
    assertEquals("reference-reconciliation-required", ambiguous.diagnostics().getFirst().field());
    assertEquals(true, ambiguous.diagnostics().getFirst().blocking());
  }

  @Test
  void doesNotReusePreviousIdentifierWhenVisibleLabelChanges() {
    SemanticReferencePlanner planner = new SemanticReferencePlanner();
    VaultReferenceResolver resolver = resolver(
        entry("vault-ref-0001", "notes/One.md", "id-one", "One", List.of()));

    SemanticReferencePlanner.PreparedSemanticBody first = planner.prepare(
        "blog/Source.md",
        "vault-ref-page",
        "[[One|same label]]",
        Optional.empty(),
        resolver);

    SemanticReferencePlanner.PreparedSemanticBody second = planner.prepare(
        "blog/Source.md",
        "vault-ref-page",
        "[[One|different label]]",
        Optional.of(PageReferenceMap.bind(first.plan(), bytes("ru"), bytes("en"))),
        resolver);

    assertEquals("[different label](ref:ref-0002)", second.markdown());
    assertEquals(List.of(), second.diagnostics());
  }

  @Test
  void flagsAmbiguousReuseWhenSequenceIndexMatchesMultipleOldIds() {
    SemanticReferencePlanner planner = new SemanticReferencePlanner();
    VaultReferenceResolver resolver = resolver(
        entry("vault-ref-0001", "notes/One.md", "id-one", "One", List.of()));

    SemanticReferencePlanner.PreparedSemanticBody ambiguous = planner.prepare(
        "blog/Source.md",
        "vault-ref-page",
        "[[One]]",
        Optional.of(new PageReferenceMap(
            PageReferenceMap.SCHEMA_VERSION,
            "vault-ref-page",
            "blog/Source.md",
            "ru",
            "en",
            List.of("ref-0001", "ref-0010"),
            Map.of(
                "ref-0001", new PageReferenceMap.Reference("vault-ref-0001", "One", "", "One"),
                "ref-0010", new PageReferenceMap.Reference("vault-ref-0001", "One", "", "One")))),
        resolver);

    assertEquals("[One](ref:ref-0001)", ambiguous.markdown());
    assertEquals(1, ambiguous.diagnostics().size());
    assertEquals("reference-reconciliation-required", ambiguous.diagnostics().getFirst().field());
    assertEquals(true, ambiguous.diagnostics().getFirst().blocking());
  }

  private static VaultReferenceResolver resolver(VaultReferenceCatalog.CatalogEntry... entries) {
    java.util.LinkedHashMap<String, VaultReferenceCatalog.CatalogEntry> mapped = new java.util.LinkedHashMap<>();
    for (VaultReferenceCatalog.CatalogEntry entry : entries) {
      mapped.put(entry.pageRef(), entry);
    }
    return new VaultReferenceResolver(new VaultReferenceCatalog(
        VaultReferenceCatalog.SCHEMA_VERSION,
        mapped));
  }

  private static VaultReferenceCatalog.CatalogEntry entry(
      String pageRef,
      String currentPath,
      String stableId,
      String title,
      List<String> aliases) {
    return new VaultReferenceCatalog.CatalogEntry(
        pageRef,
        currentPath,
        stableId,
        title,
        aliases,
        List.of(),
        VaultReferenceCatalog.STATE_ACTIVE);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
