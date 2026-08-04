package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

public final class InspectPublicationHandler {

    private static final String COMMAND = "inspect-publication";

    public BridgeResponse inspect(VaultRelativePath notePath, VaultReader vaultReader) {
        if (!notePath.isWithinVault()) {
            return BridgeResponse.blocked(COMMAND,
                    Diagnostic.blocking("note", "Note path escapes the vault root."));
        }
        if (!vaultReader.exists(notePath)) {
            return BridgeResponse.blocked(COMMAND,
                    Diagnostic.blocking("note", "Note was not found in the vault."));
        }
        throw new UnsupportedOperationException(
                "Valid-note inspection is not implemented until S02.");
    }
}
