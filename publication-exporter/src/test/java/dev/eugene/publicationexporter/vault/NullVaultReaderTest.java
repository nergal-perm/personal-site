package dev.eugene.publicationexporter.vault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
