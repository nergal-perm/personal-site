package dev.eugene.publicationexporter.manifest;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicationManifestHandlerTest {

    private static final String HIDDEN = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: hidden-essay
            id: h1
            title: Hidden Essay
            description: An essay under a normally ignored path.
            ---
            """;

    private static final String BROKEN = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: broken-essay
            id: b1
            description: Missing its title.
            ---
            """;

    private static final String FIRST = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: first-essay
            id: f1
            title: First Essay
            description: The first valid essay.
            ---
            """;

    private static final String SECOND = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: second-essay
            id: s1
            title: Second Essay
            description: The second valid essay.
            ---
            """;

    private static final String LOOKALIKE = """
            ---
            publish: false
            publicCollection: blog
            publicContentType: essay
            publicId: draft-essay
            id: d1
            title: Draft Essay
            description: Not actually selected.
            ---
            """;

    @Test
    void manifestListsEveryEntryInSortedOrderAndIsIncompleteWhenAnyEntryIsBlocked() {
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                VaultRelativePath.of(".obsidian/blog/hidden-essay.md"), HIDDEN,
                VaultRelativePath.of("blog/broken-essay.md"), BROKEN,
                VaultRelativePath.of("blog/first-essay.md"), FIRST,
                VaultRelativePath.of("blog/second-essay.md"), SECOND,
                VaultRelativePath.of("blog/draft.md"), LOOKALIKE));

        PublicationManifest manifest = new PublicationManifestHandler().manifest(vaultReader);

        assertFalse(manifest.ok());
        List<ManifestEntry> entries = manifest.entries();
        assertEquals(4, entries.size());

        ManifestEntry hidden = entries.get(0);
        assertEquals(".obsidian/blog/hidden-essay.md", hidden.path());
        assertTrue(hidden.admitted());
        assertEquals(PublicationIdentity.of("blog", "essay", "hidden-essay"), hidden.identity());
        assertEquals(List.of(), hidden.diagnostics());

        ManifestEntry broken = entries.get(1);
        assertEquals("blog/broken-essay.md", broken.path());
        assertFalse(broken.admitted());
        assertNull(broken.identity());
        assertEquals("title", broken.diagnostics().get(0).field());

        ManifestEntry first = entries.get(2);
        assertEquals("blog/first-essay.md", first.path());
        assertTrue(first.admitted());
        assertEquals(PublicationIdentity.of("blog", "essay", "first-essay"), first.identity());

        ManifestEntry second = entries.get(3);
        assertEquals("blog/second-essay.md", second.path());
        assertTrue(second.admitted());
        assertEquals(PublicationIdentity.of("blog", "essay", "second-essay"), second.identity());
    }

    @Test
    void manifestIsCompleteWhenEveryEntryAdmits() {
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/first-essay.md"), FIRST,
                VaultRelativePath.of("blog/second-essay.md"), SECOND));

        PublicationManifest manifest = new PublicationManifestHandler().manifest(vaultReader);

        assertTrue(manifest.ok());
        assertEquals(2, manifest.entries().size());
    }

    @Test
    void emptyVaultProducesACompleteEmptyManifest() {
        VaultReader vaultReader = VaultReader.createNull(Map.of());

        PublicationManifest manifest = new PublicationManifestHandler().manifest(vaultReader);

        assertTrue(manifest.ok());
        assertEquals(List.of(), manifest.entries());
    }
}
