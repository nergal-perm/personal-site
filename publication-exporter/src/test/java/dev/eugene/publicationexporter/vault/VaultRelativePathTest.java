package dev.eugene.publicationexporter.vault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void driveQualifiedPathEscapesVault() {
        assertFalse(VaultRelativePath.of("C:/etc/passwd.md").isWithinVault());
    }

    @Test
    void lowercaseDriveQualifiedPathEscapesVault() {
        assertFalse(VaultRelativePath.of("c:blog/note.md").isWithinVault());
    }

    @Test
    void colonInsideAnOrdinarySegmentStaysWithinVault() {
        assertTrue(VaultRelativePath.of("blog/note: a title.md").isWithinVault());
    }

    @Test
    void emptyPathEscapesVault() {
        assertFalse(VaultRelativePath.of("").isWithinVault());
    }

    @Test
    void soloDotSegmentEscapesVault() {
        assertFalse(VaultRelativePath.of("./blog/note.md").isWithinVault());
    }

    @Test
    void trailingSlashProducesEmptySegmentAndEscapesVault() {
        assertFalse(VaultRelativePath.of("blog/").isWithinVault());
    }

    @Test
    void nullPathIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> VaultRelativePath.of(null));
        assertEquals("value", exception.getMessage());
    }

    @Test
    void equalPathsBuiltSeparatelyAreEqual() {
        assertEquals(VaultRelativePath.of("blog/note.md"), VaultRelativePath.of("blog/note.md"));
    }
}
