package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.admission.EssayAdmission;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.note.Frontmatter;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.List;

public final class InspectPublicationHandler {

    private static final String COMMAND = "inspect-publication";
    private static final String NOT_PREPARED = "not_prepared";
    private static final String ABSENT = "absent";

    public BridgeResponse inspect(VaultRelativePath notePath, VaultReader vaultReader) {
        if (!notePath.isWithinVault()) {
            return blockedForVaultEscape();
        }
        if (!vaultReader.exists(notePath)) {
            return blockedForMissingNote();
        }
        return inspectExistingNote(notePath, vaultReader);
    }

    private BridgeResponse inspectExistingNote(VaultRelativePath notePath, VaultReader vaultReader) {
        Frontmatter frontmatter = Frontmatter.parse(vaultReader.readSource(notePath));
        EssayAdmission.Result admission = new EssayAdmission().admit(frontmatter);
        if (!admission.accepted()) {
            return blockedForAdmission(admission.diagnostics());
        }
        return acceptedEssay(admission);
    }

    private BridgeResponse blockedForVaultEscape() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("note", "Note path escapes the vault root."));
    }

    private BridgeResponse blockedForMissingNote() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("note", "Note was not found in the vault."));
    }

    private BridgeResponse blockedForAdmission(List<Diagnostic> diagnostics) {
        return BridgeResponse.blocked(COMMAND, diagnostics);
    }

    private BridgeResponse acceptedEssay(EssayAdmission.Result admission) {
        return BridgeResponse.essayInspected(
                COMMAND, NOT_PREPARED, admission.identity(),
                ABSENT, ABSENT, ABSENT, ABSENT);
    }
}
