package dev.eugene.publicationexporter.vault;

import org.junit.jupiter.api.Test;

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
}
