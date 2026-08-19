package dev.eugene.publicationexporter.legacy;

import java.util.Objects;

public record GenerationActivationStores(MigrationJournalStore journal, MigrationCatalogStore catalog) {

    public GenerationActivationStores {
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(catalog, "catalog");
    }
}
