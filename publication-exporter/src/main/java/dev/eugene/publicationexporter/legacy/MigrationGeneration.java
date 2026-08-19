package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record MigrationGeneration(String inventorySha256, List<PublicationIdentity> identities,
        int completedSteps, MigrationState state) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public MigrationGeneration {
        Objects.requireNonNull(inventorySha256, "inventorySha256");
        Objects.requireNonNull(identities, "identities");
        Objects.requireNonNull(state, "state");
        if (!SHA256.matcher(inventorySha256).matches()) {
            throw new IllegalArgumentException("inventorySha256 must be a lowercase SHA-256 fingerprint");
        }
        identities = List.copyOf(identities);
        if (identities.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("identities must not contain null");
        }
        Set<PublicationIdentity> unique = new HashSet<>(identities);
        if (unique.size() != identities.size()) {
            throw new IllegalArgumentException("identities must not contain duplicates");
        }
        if (completedSteps < 0 || completedSteps > identities.size()) {
            throw new IllegalArgumentException("completedSteps must be within identities");
        }
        if (state != MigrationState.RUNNING && completedSteps != identities.size()) {
            throw new IllegalArgumentException("terminal generations must contain all completed steps");
        }
    }

    public MigrationGeneration advance() {
        requireRunning();
        if (completedSteps == identities.size()) {
            throw new MigrationRecoveryException("Migration generation has no remaining steps.");
        }
        return new MigrationGeneration(inventorySha256, identities, completedSteps + 1, MigrationState.RUNNING);
    }

    public MigrationGeneration sealed() {
        requireRunning();
        if (completedSteps != identities.size()) {
            throw new MigrationRecoveryException("Migration generation cannot be sealed before every identity is applied.");
        }
        return new MigrationGeneration(inventorySha256, identities, identities.size(), MigrationState.SEALED);
    }

    public MigrationGeneration rolledBack() {
        requireRunning();
        return new MigrationGeneration(inventorySha256, identities, identities.size(), MigrationState.ROLLED_BACK);
    }

    public boolean isRunning() {
        return state == MigrationState.RUNNING;
    }

    public boolean isSealed() {
        return state == MigrationState.SEALED;
    }

    public boolean isRolledBack() {
        return state == MigrationState.ROLLED_BACK;
    }

    private void requireRunning() {
        if (!isRunning()) {
            throw new MigrationRecoveryException("Terminal migration generations cannot be rewritten.");
        }
    }
}
