package dev.eugene.publicationexporter.legacy;

import java.util.Objects;
import java.util.Optional;

public final class NullMigrationCatalogStore implements MigrationCatalogStore {

    private Optional<MigrationGeneration> generation = Optional.empty();

    public NullMigrationCatalogStore() {
    }

    public NullMigrationCatalogStore(MigrationGeneration initial) {
        generation = Optional.of(Objects.requireNonNull(initial, "generation"));
    }

    @Override
    public Optional<MigrationGeneration> read() {
        return generation;
    }

    @Override
    public void save(MigrationGeneration value) {
        generation = Optional.of(Objects.requireNonNull(value, "generation"));
    }
}
