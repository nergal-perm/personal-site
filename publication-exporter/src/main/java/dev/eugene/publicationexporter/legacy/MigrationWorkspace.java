package dev.eugene.publicationexporter.legacy;

import java.nio.file.Path;

public interface MigrationWorkspace {

    default void preflight(MigrationGeneration generation) {
    }

    MigrationPreimage capture(MigrationGeneration generation);

    /**
     * Applies one generation step. Implementations must make replay of the same
     * generation and cursor idempotent because interruption can occur after the
     * workspace mutation and before the cursor journal replacement.
     */
    void apply(MigrationGeneration generation, MigrationPreimage preimage, int step);

    default void apply(MigrationGeneration generation, int step) {
        apply(generation, capture(generation), step);
    }

    void verify(MigrationGeneration generation, MigrationPreimage preimage);

    void restore(MigrationPreimage preimage);

    static MigrationWorkspace createNull() {
        return new NullMigrationWorkspace();
    }

    static MigrationWorkspace create(Path reviewRoot) {
        return new FilesystemMigrationWorkspace(reviewRoot);
    }
}
