package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class DirectTargetIdentityCheckTest {

    @Test
    void selfLinkIsExcludedFromComparisonAgainstTheSourcesOwnId() {
        VaultSourceIdentityIndex index = VaultSourceIdentityIndex.from(VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/My Essay.md"), "---\npublish: true\nid: 8f2c-my-essay\n---\nBody.")));

        DirectTargetIdentityOutcome outcome = DirectTargetIdentityCheck.verify(
                "My Essay", "8f2c-my-essay", Set.of("My Essay"), index);

        assertTrue(outcome.resolve(() -> true, reason -> false));
    }

    @Test
    void twoDistinctTargetsSharingAnIdWithEachOtherAreBlocked() {
        VaultSourceIdentityIndex index = VaultSourceIdentityIndex.from(VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/Target One.md"), "---\npublish: false\nid: shared\n---\nOne.",
                VaultRelativePath.of("blog/Target Two.md"), "---\npublish: false\nid: shared\n---\nTwo.")));

        DirectTargetIdentityOutcome outcome = DirectTargetIdentityCheck.verify(
                "My Essay", "8f2c-my-essay", Set.of("Target One", "Target Two"), index);

        assertTrue(outcome.resolve(() -> false, reason -> true));
    }

    @Test
    void aTargetStemWithNoMatchingVaultFileIsSkippedRatherThanBlocked() {
        VaultSourceIdentityIndex index = VaultSourceIdentityIndex.from(VaultReader.createNull());

        DirectTargetIdentityOutcome outcome = DirectTargetIdentityCheck.verify(
                "My Essay", "8f2c-my-essay", Set.of("Typo Target"), index);

        assertTrue(outcome.resolve(() -> true, reason -> false));
    }
}
