package dev.eugene.astroexport.references;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class VaultReferenceCatalogTest {
  @TempDir
  Path temp;

  @Test
  void exactPathReusesIdentityAndConfirmedStableIdReconcilesRename() throws IOException {
    Path vault = temp.resolve("vault");
    writeNote(vault.resolve("notes/Old.md"), """
        ---
        id: stable-note
        aliases:
          - Known
        ---
        """);

    VaultReferenceCatalog catalog = VaultReferenceCatalog.empty();
    VaultReferenceCatalog first = catalog.reconcile(vault, VaultNoteDescriptor.scan(vault));
    String ref = first.requireByCurrentPath("notes/Old.md").pageRef();

    Files.move(vault.resolve("notes/Old.md"), vault.resolve("notes/New.md"));

    VaultReferenceCatalog renamed = first.reconcile(vault, VaultNoteDescriptor.scan(vault));
    VaultReferenceCatalog.CatalogEntry reconciled = renamed.requireByCurrentPath("notes/New.md");

    assertEquals(ref, reconciled.pageRef());
    assertEquals(List.of("notes/Old.md"), reconciled.previousPaths());
  }

  @Test
  void storesAndLoadsCatalogV1Atomically() throws IOException {
    Path review = temp.resolve("review");
    VaultReferenceCatalog catalog = new VaultReferenceCatalog(
        VaultReferenceCatalog.SCHEMA_VERSION,
        Map.of("vault-ref-0001", new VaultReferenceCatalog.CatalogEntry(
            "vault-ref-0001",
            "notes/One.md",
            "stable-note",
            "",
            List.of("Alias"),
            List.of(),
            VaultReferenceCatalog.STATE_ACTIVE)));

    catalog.writeAtomically(review);

    assertTrue(Files.exists(VaultReferenceCatalog.catalogPath(review)));
    VaultReferenceCatalog loaded = VaultReferenceCatalog.load(review);
    assertEquals("catalog-v1.json", VaultReferenceCatalog.catalogPath(review).getFileName().toString());
    assertEquals(catalog.schemaVersion(), loaded.schemaVersion());
    assertEquals(VaultReferenceCatalog.STATE_ACTIVE, loaded.entries().get("vault-ref-0001").state());
    assertEquals(List.of("Alias"), loaded.entries().get("vault-ref-0001").aliases());
  }

  @Test
  void preservesRemovedNotesAsTombstone() throws IOException {
    Path vault = temp.resolve("vault-two");
    writeNote(vault.resolve("notes/One.md"), """
        ---
        id: note-one
        ---
        """);

    VaultReferenceCatalog baseline = VaultReferenceCatalog.empty()
        .reconcile(vault, VaultNoteDescriptor.scan(vault));
    String pageRef = baseline.requireByCurrentPath("notes/One.md").pageRef();

    Files.delete(vault.resolve("notes/One.md"));
    VaultReferenceCatalog deleted = baseline.reconcile(vault, VaultNoteDescriptor.scan(vault));

    assertEquals(VaultReferenceCatalog.STATE_TOMBSTONE, deleted.entries().get(pageRef).state());
  }

  private static void writeNote(Path notePath, String body) throws IOException {
    Files.createDirectories(notePath.getParent());
    Files.writeString(notePath, body);
  }
}
