package dev.eugene.publicationexporter.legacy;

import java.util.Optional;
import java.nio.file.Path;

public interface MigrationCatalogStore {

    default void preflight() {
    }

    default boolean exists() {
        return read().isPresent();
    }

    Optional<MigrationGeneration> read();

    void save(MigrationGeneration generation);

    static MigrationCatalogStore createNull() {
        return new NullMigrationCatalogStore();
    }

    static MigrationCatalogStore create(Path reviewRoot) {
        return new FilesystemMigrationCatalogStore(reviewRoot);
    }
}
