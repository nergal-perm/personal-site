package dev.eugene.publicationexporter.legacy;

import java.util.Objects;
import java.util.Optional;

public final class NullMigrationJournalStore implements MigrationJournalStore {

    private Optional<MigrationGeneration> generation;
    private Optional<MigrationPreimage> preimage = Optional.empty();

    public NullMigrationJournalStore() {
        generation = Optional.empty();
    }

    @Override
    public Optional<MigrationGeneration> read() {
        return generation;
    }

    @Override
    public void save(MigrationGeneration value) {
        MigrationGeneration saved = Objects.requireNonNull(value, "generation");
        MigrationPreimage recorded = preimage.filter(snapshot -> snapshot.belongsTo(saved)).orElseThrow(
                () -> new MigrationRecoveryException("Journal cursor transition requires its captured preimage."));
        if (!recorded.belongsTo(saved)) {
            throw new MigrationRecoveryException("Journal preimage does not belong to the generation.");
        }
        generation = Optional.of(saved);
    }

    @Override
    public void save(MigrationGeneration value, MigrationPreimage recorded) {
        MigrationGeneration saved = Objects.requireNonNull(value, "generation");
        MigrationPreimage snapshot = Objects.requireNonNull(recorded, "preimage");
        if (!snapshot.belongsTo(saved)) {
            throw new IllegalArgumentException("Preimage must belong to the saved generation");
        }
        preimage = Optional.of(snapshot);
        generation = Optional.of(saved);
    }

    @Override
    public Optional<MigrationPreimage> preimage() {
        return preimage;
    }
}
