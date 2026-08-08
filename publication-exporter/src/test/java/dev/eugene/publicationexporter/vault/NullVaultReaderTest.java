package dev.eugene.publicationexporter.vault;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullVaultReaderTest {

    @Test
    void defaultConfigurationReportsNothingExists() {
        VaultReader reader = new NullVaultReader();
        assertFalse(reader.exists(VaultRelativePath.of("blog/anything.md")));
    }

    @Test
    void configuredPathReportsExists() {
        VaultRelativePath existing = VaultRelativePath.of("blog/real-note.md");
        VaultReader reader = new NullVaultReader(existing);
        assertTrue(reader.exists(existing));
        assertFalse(reader.exists(VaultRelativePath.of("blog/other.md")));
    }

    @Test
    void interfaceFactoryDefaultsToNothingExists() {
        VaultReader reader = VaultReader.createNull();
        assertFalse(reader.exists(VaultRelativePath.of("blog/anything.md")));
    }

    @Test
    void configuredNoteReadsBackItsSourceText() {
        VaultRelativePath path = VaultRelativePath.of("blog/real-note.md");
        VaultReader reader = VaultReader.createNull(Map.of(path, "---\npublish: true\n---\n"));

        assertEquals("---\npublish: true\n---\n", reader.readSource(path));
    }

    @Test
    void pathSeededWithoutContentReadsBackAsEmptySource() {
        VaultRelativePath path = VaultRelativePath.of("blog/real-note.md");
        VaultReader reader = VaultReader.createNull(path);

        assertEquals("", reader.readSource(path));
    }

    @Test
    void readingSourceForAnUnseededPathThrows() {
        VaultReader reader = VaultReader.createNull();
        assertThrows(NoSuchElementException.class,
                () -> reader.readSource(VaultRelativePath.of("blog/missing.md")));
    }

    @Test
    void listPublishCandidatesReturnsOnlyNotesWithPublishTrue() {
        VaultRelativePath published = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath draft = VaultRelativePath.of("blog/draft.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                published, "---\npublish: true\n---\nBody.",
                draft, "---\npublish: false\n---\nBody."));

        assertEquals(List.of(published), vaultReader.listPublishCandidates());
    }

    @Test
    void listPublishCandidatesExcludesPublishedNonMarkdownPaths() {
        VaultRelativePath published = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath nonMarkdown = VaultRelativePath.of("blog/note.txt");
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                published, "---\npublish: true\n---\nBody.",
                nonMarkdown, "---\npublish: true\n---\nNot a note."));

        assertEquals(List.of(published), vaultReader.listPublishCandidates());
    }
}
