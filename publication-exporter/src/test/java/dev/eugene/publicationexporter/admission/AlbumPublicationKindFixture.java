package dev.eugene.publicationexporter.admission;

import java.util.Objects;

public final class AlbumPublicationKindFixture {

    private final String name;
    private final String noteSource;
    private final boolean expectedAccepted;

    private AlbumPublicationKindFixture(String name, String noteSource, boolean expectedAccepted) {
        this.name = Objects.requireNonNull(name, "name");
        this.noteSource = Objects.requireNonNull(noteSource, "noteSource");
        this.expectedAccepted = expectedAccepted;
    }

    public static AlbumPublicationKindFixture accepted(String name, String noteSource) {
        return new AlbumPublicationKindFixture(name, noteSource, true);
    }

    public static AlbumPublicationKindFixture blocked(String name, String noteSource) {
        return new AlbumPublicationKindFixture(name, noteSource, false);
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
