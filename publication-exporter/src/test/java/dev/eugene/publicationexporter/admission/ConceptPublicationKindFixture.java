package dev.eugene.publicationexporter.admission;

import java.util.Objects;

public final class ConceptPublicationKindFixture {

    private final String name;
    private final String noteSource;
    private final boolean expectedAccepted;

    private ConceptPublicationKindFixture(String name, String noteSource, boolean expectedAccepted) {
        this.name = Objects.requireNonNull(name, "name");
        this.noteSource = Objects.requireNonNull(noteSource, "noteSource");
        this.expectedAccepted = expectedAccepted;
    }

    public static ConceptPublicationKindFixture accepted(String name, String noteSource) {
        return new ConceptPublicationKindFixture(name, noteSource, true);
    }

    public static ConceptPublicationKindFixture blocked(String name, String noteSource) {
        return new ConceptPublicationKindFixture(name, noteSource, false);
    }

    public String name() {
        return name;
    }

    public String noteSource() {
        return noteSource;
    }

    public boolean expectedAccepted() {
        return expectedAccepted;
    }

    @Override
    public String toString() {
        return name;
    }
}
