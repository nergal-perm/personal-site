package dev.eugene.publicationexporter.legacy;

import java.util.Objects;

public final class LegacyMigrationDecisionValidator {

    private final LegacyWorkspaceInventoryHandler inventory;
    private final LegacyMigrationDecisionCodec codec;

    public LegacyMigrationDecisionValidator(
            LegacyWorkspaceInventoryHandler inventory, LegacyMigrationDecisionCodec codec) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public MigrationDecisionSet validate(String decisionJson) {
        return validate(decisionJson, inventory.inspect());
    }

    public MigrationDecisionSet validate(String decisionJson, LegacyWorkspaceInventory currentInventory) {
        MigrationDecisionSet decision = codec.decisionsFrom(decisionJson);
        rejectStaleFingerprint(decision, Objects.requireNonNull(currentInventory, "currentInventory"));
        return decision;
    }

    private void rejectStaleFingerprint(
            MigrationDecisionSet decision, LegacyWorkspaceInventory currentInventory) {
        if (!decision.inventorySha256().equals(currentInventory.inventorySha256())) {
            throw new LegacyMigrationDecisionException(
                    "Decision inventory fingerprint is stale; inspect the current inventory and provide a fresh decision.");
        }
    }
}
