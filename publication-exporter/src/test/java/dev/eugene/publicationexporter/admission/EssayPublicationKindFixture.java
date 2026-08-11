package dev.eugene.publicationexporter.admission;

import java.util.List;
import java.util.Objects;

public final class EssayPublicationKindFixture {

    private final String name;
    private final String noteSource;
    private final boolean expectedAccepted;
    private final List<String> expectedBlockedFields;

    private EssayPublicationKindFixture(
            String name, String noteSource, boolean expectedAccepted, List<String> expectedBlockedFields) {
        this.name = Objects.requireNonNull(name, "name");
        this.noteSource = Objects.requireNonNull(noteSource, "noteSource");
        this.expectedAccepted = expectedAccepted;
        this.expectedBlockedFields = List.copyOf(Objects.requireNonNull(expectedBlockedFields, "expectedBlockedFields"));
    }

    public static EssayPublicationKindFixture accepted(String name, String noteSource) {
        return new EssayPublicationKindFixture(name, noteSource, true, List.of());
    }

    public static EssayPublicationKindFixture blocked(String name, String noteSource, List<String> expectedBlockedFields) {
        return new EssayPublicationKindFixture(name, noteSource, false, expectedBlockedFields);
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

    public List<String> expectedBlockedFields() {
        return expectedBlockedFields;
    }

    @Override
    public String toString() {
        return name;
    }
}
