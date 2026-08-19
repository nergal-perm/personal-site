package dev.eugene.publicationexporter.legacy;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

public final class SchemaActivationCheck {

    private final Optional<String> blockingReason;

    private SchemaActivationCheck(Optional<String> blockingReason) {
        this.blockingReason = Objects.requireNonNull(blockingReason, "blockingReason");
    }

    public static SchemaActivationCheck current() {
        return new SchemaActivationCheck(Optional.empty());
    }

    public static SchemaActivationCheck legacy(String blockingReason) {
        return new SchemaActivationCheck(Optional.of(Objects.requireNonNull(blockingReason, "blockingReason")));
    }

    public boolean requiresMigration() {
        return blockingReason.isPresent();
    }

    public boolean isCurrent() {
        return !requiresMigration();
    }

    public boolean isLegacy() {
        return requiresMigration();
    }

    public String blockingReason() {
        return blockingReason.orElseThrow(
                () -> new NoSuchElementException("No blocking reason: this workspace is current."));
    }
}
