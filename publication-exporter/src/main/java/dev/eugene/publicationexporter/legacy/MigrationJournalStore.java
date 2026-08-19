package dev.eugene.publicationexporter.legacy;

import java.util.Optional;
import java.nio.file.Path;

public interface MigrationJournalStore {

    default void preflight() {
    }

    default boolean exists() {
        return read().isPresent();
    }

    Optional<MigrationGeneration> read();

    void save(MigrationGeneration generation);

    void save(MigrationGeneration generation, MigrationPreimage preimage);

    Optional<MigrationPreimage> preimage();

    static MigrationJournalStore createNull() {
        return new NullMigrationJournalStore();
    }

    static MigrationJournalStore create(Path reviewRoot) {
        return new FilesystemMigrationJournalStore(reviewRoot);
    }

}
