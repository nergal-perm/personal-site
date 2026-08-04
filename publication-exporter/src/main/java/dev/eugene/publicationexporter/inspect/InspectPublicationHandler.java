package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

public final class InspectPublicationHandler {

    private static final String COMMAND = "inspect-publication";

    public BridgeResponse inspect(VaultRelativePath notePath, VaultReader vaultReader) {
        if (!notePath.isWithinVault()) {
            return blockedForVaultEscape();
        }
        if (!vaultReader.exists(notePath)) {
            return blockedForMissingNote();
        }
        throw new UnsupportedOperationException(
                "Valid-note inspection is not implemented until S02.");
    }

    private BridgeResponse blockedForVaultEscape() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("note", "Note path escapes the vault root."));
    }

    private BridgeResponse blockedForMissingNote() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("note", "Note was not found in the vault."));
    }
}
