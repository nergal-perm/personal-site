package dev.eugene.publicationexporter.admission;

import java.util.List;

public record BookPublicationKindFixture(
        String name, String noteSource, boolean expectedAccepted, List<String> expectedBlockedFields) {

    public static BookPublicationKindFixture accepted(String name, String noteSource) {
        return new BookPublicationKindFixture(name, noteSource, true, List.of());
    }

    public static BookPublicationKindFixture blocked(
            String name, String noteSource, List<String> expectedBlockedFields) {
        return new BookPublicationKindFixture(name, noteSource, false, List.copyOf(expectedBlockedFields));
    }
}
