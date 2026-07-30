package dev.eugene.astroexport.references;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class VaultReferenceResolverTest {

  @Test
  void resolvesByPathBeforeIdAndAliasLayers() {
    VaultReferenceCatalog catalog = catalog(
        entry("vault-ref-0001", "notes/notes/Stable.md", "stable-id", "Alpha", List.of("One")),
        entry("vault-ref-0002", "other.md", "other", "Beta", List.of("notes/notes/Stable")),
        entry("vault-ref-0003", "notes/Alpha.md", null, "Gamma", List.of("stable-id")));

    VaultReferenceResolver resolver = new VaultReferenceResolver(catalog);

    assertEquals("vault-ref-0001",
        resolver.resolve("source.md", "notes/notes/Stable").pageRef());
    assertEquals("vault-ref-0002",
        resolver.resolve("source.md", "other").pageRef());
    assertEquals("vault-ref-0001",
        resolver.resolve("source.md", "stable-id").pageRef());
  }

  @Test
  void duplicateAliasesAreAmbiguousAndMissingTargetsStayUnresolved() {
    VaultReferenceResolver resolver = new VaultReferenceResolver(catalog(
        entry("vault-ref-0001", "notes/One.md", null, "One", List.of("Shared")),
        entry("vault-ref-0002", "notes/Two.md", null, "Two", List.of("Shared"))));

    assertEquals(VaultReferenceResolver.Status.AMBIGUOUS,
        resolver.resolve("blog/A.md", "Shared").status());
    assertEquals(VaultReferenceResolver.Status.UNRESOLVED,
        resolver.resolve("blog/A.md", "Future").status());
  }

  @Test
  void rejectsAmbiguousTimestampStrippedTitleCollision() {
    VaultReferenceResolver resolver = new VaultReferenceResolver(catalog(
        entry("vault-ref-0001", "notes/202301010000 Alpha.md", "a", "Nope", List.of()),
        entry("vault-ref-0002", "notes/Alpha.md", "b", "Nope", List.of()),
        entry("vault-ref-0003", "notes/202301010000 Beta.md", "c", "Nope", List.of())));

    assertEquals(
        VaultReferenceResolver.Status.AMBIGUOUS,
        resolver.resolve("blog/A.md", "202301010000 Alpha").status());
  }

  private static VaultReferenceCatalog catalog(VaultReferenceCatalog.CatalogEntry... entries) {
    Map<String, VaultReferenceCatalog.CatalogEntry> mapped = new java.util.LinkedHashMap<>();
    for (VaultReferenceCatalog.CatalogEntry entry : entries) {
      mapped.put(entry.pageRef(), entry);
    }
    return new VaultReferenceCatalog(VaultReferenceCatalog.SCHEMA_VERSION, mapped);
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
}
