package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PrivateNoteIdentityIndexTest {

    @Test
    void identityForReturnsEmptyWhenNoFileMatchesTheStem() {
        PrivateNoteIdentityIndex index = PrivateNoteIdentityIndex.from(VaultReader.createNull());

        assertTrue(index.identityFor("Nonexistent").isEmpty());
    }

    @Test
    void identityForReturnsEmptyWhenTwoFilesShareTheSameStem() {
        String noteInBlog = "---\npublish: false\nid: one\n---\nFirst.";
        String noteInArchive = "---\npublish: false\nid: two\n---\nSecond.";
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/Draft.md"), noteInBlog,
                VaultRelativePath.of("archive/Draft.md"), noteInArchive));

        PrivateNoteIdentityIndex index = PrivateNoteIdentityIndex.from(vaultReader);

        assertTrue(index.identityFor("Draft").isEmpty());
    }

    @Test
    void presentIdentityHasAnEmptySourceIdWhenFrontmatterHasNoIdKey() {
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/Draft.md"), "---\npublish: false\n---\nNo id."));

        PrivateNoteIdentityIndex index = PrivateNoteIdentityIndex.from(vaultReader);

        TargetIdentity identity = index.identityFor("Draft").orElseThrow();
        assertEquals(Optional.empty(), identity.sourceId());
    }

    @Test
    void identityForReturnsTheFrontmatterSourceIdWhenPresent() {
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/Draft.md"), "---\npublish: false\nid: 4c1b-draft\n---\nBody."));

        PrivateNoteIdentityIndex index = PrivateNoteIdentityIndex.from(vaultReader);

        TargetIdentity identity = index.identityFor("Draft").orElseThrow();
        assertEquals(Optional.of("4c1b-draft"), identity.sourceId());
    }
}
