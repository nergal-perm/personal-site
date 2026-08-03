package dev.eugene.publicationexporter.vault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultRelativePathTest {

    @Test
    void plainRelativePathIsWithinVault() {
        assertTrue(VaultRelativePath.of("blog/does-not-exist.md").isWithinVault());
    }

    @Test
    void parentSegmentEscapesVault() {
        assertFalse(VaultRelativePath.of("../../etc/passwd.md").isWithinVault());
    }

    @Test
    void absolutePathEscapesVault() {
        assertFalse(VaultRelativePath.of("/etc/passwd.md").isWithinVault());
    }

    @Test
    void backslashEscapesVault() {
        assertFalse(VaultRelativePath.of("blog\\..\\secrets.md").isWithinVault());
    }
}
