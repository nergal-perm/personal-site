package dev.eugene.publicationexporter.contract;

import dev.eugene.publicationexporter.admission.BookPublicationKindFixture;
import dev.eugene.publicationexporter.admission.BookPublicationKindFixtures;
import dev.eugene.publicationexporter.admission.ClaimPublicationKindFixture;
import dev.eugene.publicationexporter.admission.ClaimPublicationKindFixtures;
import dev.eugene.publicationexporter.admission.EssayPublicationKindFixture;
import dev.eugene.publicationexporter.admission.EssayPublicationKindFixtures;
import dev.eugene.publicationexporter.admission.NotePublicationKindFixture;
import dev.eugene.publicationexporter.admission.NotePublicationKindFixtures;
import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicationContractConformanceTest {

    private final NoteIntake intake = new NoteIntake(PublicationKinds.installed());

    @ParameterizedTest(name = "{0}")
    @MethodSource("allAdmissionFixtures")
    void contractVerdictAgreesWithFixtureAndRuntimeValidator(EssayPublicationKindFixture fixture) {
        MarkdownNote note = MarkdownNote.parse(fixture.noteSource());
        KindContract essayKind = new PublicationContractWriter().write().kinds().stream()
                .filter(kind -> kind.collection().equals("blog") && kind.contentType().equals("essay"))
                .findFirst()
                .orElseThrow();

        boolean contractAccepts = contractAccepts(essayKind, note);
        VaultRelativePath path = VaultRelativePath.of("blog/" + fixture.name() + ".md");
        boolean runtimeAccepts = intake.admit(path, VaultReader.createNull(Map.of(path, fixture.noteSource())))
                .accepted();

        assertEquals(fixture.expectedAccepted(), contractAccepts, "contract verdict for " + fixture.name());
        assertEquals(fixture.expectedAccepted(), runtimeAccepts, "runtime verdict for " + fixture.name());
        assertEquals(contractAccepts, runtimeAccepts, "contract/runtime agreement for " + fixture.name());
    }

    @ParameterizedTest(name = "blog/note {0}")
    @MethodSource("allNoteAdmissionFixtures")
    void noteContractVerdictAgreesWithFixtureAndRuntimeValidator(NotePublicationKindFixture fixture) {
        MarkdownNote note = MarkdownNote.parse(fixture.noteSource());
        KindContract noteKind = new PublicationContractWriter().write().kinds().stream()
                .filter(kind -> kind.collection().equals("blog") && kind.contentType().equals("note"))
                .findFirst()
                .orElseThrow();

        boolean contractAccepts = contractAccepts(noteKind, note);
        VaultRelativePath path = VaultRelativePath.of("blog/" + fixture.name() + ".md");
        boolean runtimeAccepts = intake.admit(path, VaultReader.createNull(Map.of(path, fixture.noteSource())))
                .accepted();

        assertEquals(fixture.expectedAccepted(), contractAccepts, "contract verdict for " + fixture.name());
        assertEquals(fixture.expectedAccepted(), runtimeAccepts, "runtime verdict for " + fixture.name());
        assertEquals(contractAccepts, runtimeAccepts, "contract/runtime agreement for " + fixture.name());
    }

    @ParameterizedTest(name = "blog/claim {0}")
    @MethodSource("allClaimAdmissionFixtures")
    void claimContractVerdictAgreesWithFixtureAndRuntimeValidator(ClaimPublicationKindFixture fixture) {
        MarkdownNote note = MarkdownNote.parse(fixture.noteSource());
        KindContract claimKind = new PublicationContractWriter().write().kinds().stream()
                .filter(kind -> kind.collection().equals("blog") && kind.contentType().equals("claim"))
                .findFirst()
                .orElseThrow();

        boolean contractAccepts = contractAccepts(claimKind, note);
        VaultRelativePath path = VaultRelativePath.of("blog/" + fixture.name() + ".md");
        boolean runtimeAccepts = intake.admit(path, VaultReader.createNull(Map.of(path, fixture.noteSource())))
                .accepted();

        assertEquals(fixture.expectedAccepted(), contractAccepts, "contract verdict for " + fixture.name());
        assertEquals(fixture.expectedAccepted(), runtimeAccepts, "runtime verdict for " + fixture.name());
        assertEquals(contractAccepts, runtimeAccepts, "contract/runtime agreement for " + fixture.name());
    }

    @ParameterizedTest(name = "bibliography/book {0}")
    @MethodSource("allBookAdmissionFixtures")
    void bookContractVerdictAgreesWithFixtureAndRuntimeValidator(BookPublicationKindFixture fixture) {
        MarkdownNote note = MarkdownNote.parse(fixture.noteSource());
        KindContract bookKind = new PublicationContractWriter().write().kinds().stream()
                .filter(kind -> kind.collection().equals("bibliography") && kind.contentType().equals("book"))
                .findFirst()
                .orElseThrow();

        boolean contractAccepts = contractAccepts(bookKind, note);
        VaultRelativePath path = VaultRelativePath.of("bibliography/" + fixture.name() + ".md");
        boolean runtimeAccepts = intake.admit(path, VaultReader.createNull(Map.of(path, fixture.noteSource())))
                .accepted();

        assertEquals(fixture.expectedAccepted(), contractAccepts, "contract verdict for " + fixture.name());
        assertEquals(fixture.expectedAccepted(), runtimeAccepts, "runtime verdict for " + fixture.name());
        assertEquals(contractAccepts, runtimeAccepts, "contract/runtime agreement for " + fixture.name());
    }

    @ParameterizedTest(name = "concepts/concept {0}")
    @MethodSource("allConceptAdmissionFixtures")
    void conceptContractVerdictAgreesWithFixtureAndRuntimeValidator(ConceptPublicationKindFixture fixture) {
        MarkdownNote note = MarkdownNote.parse(fixture.noteSource());
        KindContract conceptKind = new PublicationContractWriter().write().kinds().stream()
                .filter(kind -> kind.collection().equals("concepts") && kind.contentType().equals("concept"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "publicCollection/publicContentType is not a supported publication kind"));

        boolean contractAccepts = contractAccepts(conceptKind, note);
        VaultRelativePath path = VaultRelativePath.of("concepts/" + fixture.name() + ".md");
        boolean runtimeAccepts = intake.admit(path, VaultReader.createNull(Map.of(path, fixture.noteSource())))
                .accepted();

        assertEquals(fixture.expectedAccepted(), contractAccepts, "contract verdict for " + fixture.name());
        assertEquals(fixture.expectedAccepted(), runtimeAccepts, "runtime verdict for " + fixture.name());
        assertEquals(contractAccepts, runtimeAccepts, "contract/runtime agreement for " + fixture.name());
    }

    private static Stream<NotePublicationKindFixture> allNoteAdmissionFixtures() {
        return NotePublicationKindFixtures.all().stream();
    }

    private static Stream<ClaimPublicationKindFixture> allClaimAdmissionFixtures() {
        return ClaimPublicationKindFixtures.all().stream();
    }

    private static Stream<BookPublicationKindFixture> allBookAdmissionFixtures() {
        return BookPublicationKindFixtures.all().stream();
    }

    private static Stream<ConceptPublicationKindFixture> allConceptAdmissionFixtures() {
        return Stream.of(
                ConceptPublicationKindFixture.accepted("validConceptWithoutRelations", """
                        ---
                        publish: true
                        publicCollection: concepts
                        publicContentType: concept
                        publicId: concept-example
                        id: 4bc5-concept-example
                        title: Core Concept
                        description: A valid public concept.
                        ---
                        """),
                ConceptPublicationKindFixture.accepted("conceptWithRelationsAndExamples", """
                        ---
                        publish: true
                        publicCollection: concepts
                        publicContentType: concept
                        publicId: concept-with-relations
                        id: 4bc5-concept-relations
                        title: Concept with Relations
                        description: A valid public concept with relation data.
                        relations:
                          - name: parent
                            relation: implies
                        examples:
                          - a first relation example
                          - a second relation example
                        ---
                        """),
                ConceptPublicationKindFixture.blocked("conceptWithMalformedRelations", """
                        ---
                        publish: true
                        publicCollection: concepts
                        publicContentType: concept
                        publicId: concept-bad-relations
                        id: 4bc5-concept-bad-relations
                        title: Concept with Malformed Relations
                        description: A concept with malformed relation entries.
                        relations:
                          - relation: implies
                        ---
                        """));
    }

    private static Stream<EssayPublicationKindFixture> allAdmissionFixtures() {
        return Stream.concat(EssayPublicationKindFixtures.all().stream(), Stream.of(
                EssayPublicationKindFixture.blocked("unpublished", """
                        ---
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("publish")),
                EssayPublicationKindFixture.blocked("wrongCollection", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("publicCollection", "publicContentType")),
                EssayPublicationKindFixture.blocked("wrongContentType", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: book
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("publicContentType"))));
    }

    private boolean contractAccepts(KindContract kind, MarkdownNote note) {
        for (FieldContract field : kind.requiredFields()) {
            if (!fieldSatisfied(field, note)) {
                return false;
            }
        }
        for (FieldContract field : kind.optionalFields()) {
            if (fieldPresent(field.name(), note) && !fieldSatisfied(field, note)) {
                return false;
            }
        }
        for (String blockedField : kind.blockedFields()) {
            if (fieldPresent(blockedField, note)) {
                return false;
            }
        }
        return true;
    }

    private boolean fieldPresent(String key, MarkdownNote note) {
        return note.string(key).isPresent()
                || note.structuredField(key) != MarkdownNote.StructuredField.ABSENT
                || !note.listOfScalars(key).isEmpty();
    }

    private boolean fieldSatisfied(FieldContract field, MarkdownNote note) {
        return switch (field.type()) {
            case BOOLEAN -> field.allowedValues().contains(String.valueOf(note.flag(field.name())));
            case STRING -> stringFieldSatisfied(field, note.string(field.name()).orElse(null));
            case STRING_LIST -> stringListFieldSatisfied(field, note.listOfScalars(field.name()));
            case STRUCTURED_LIST -> structuredListFieldSatisfied(field, note.listOfMaps(field.name()));
        };
    }

    private boolean stringFieldSatisfied(FieldContract field, String value) {
        if (value == null) {
            return false;
        }
        if (field.nonBlank()) {
            return !value.isBlank();
        }
        if (field.pattern() != null) {
            return Pattern.compile(field.pattern()).matcher(value).matches();
        }
        return field.allowedValues().contains(value);
    }

    private boolean stringListFieldSatisfied(FieldContract field, List<String> values) {
        if (values.isEmpty()) {
            return false;
        }
        if (field.nonBlank() && values.stream().anyMatch(String::isBlank)) {
            return false;
        }
        return true;
    }

    private boolean structuredListFieldSatisfied(FieldContract field, List<Map<String, String>> values) {
        if (values.isEmpty()) {
            return false;
        }
        if (field.structuredMembers() == null || field.structuredMembers().isEmpty()) {
            return false;
        }
        for (Map<String, String> value : values) {
            for (String member : field.structuredMembers()) {
                if (value.get(member) == null || value.get(member).isBlank()) {
                    return false;
                }
            }
            if (!value.keySet().containsAll(field.structuredMembers())
                    || !field.structuredMembers().containsAll(value.keySet())) {
                return false;
            }
        }
        return true;
    }

    private static final class ConceptPublicationKindFixture {

        private final String name;
        private final String noteSource;
        private final boolean expectedAccepted;

        private ConceptPublicationKindFixture(String name, String noteSource, boolean expectedAccepted) {
            this.name = name;
            this.noteSource = noteSource;
            this.expectedAccepted = expectedAccepted;
        }

        static ConceptPublicationKindFixture accepted(String name, String noteSource) {
            return new ConceptPublicationKindFixture(name, noteSource, true);
        }

        static ConceptPublicationKindFixture blocked(String name, String noteSource) {
            return new ConceptPublicationKindFixture(name, noteSource, false);
        }

        String name() {
            return name;
        }

        String noteSource() {
            return noteSource;
        }

        boolean expectedAccepted() {
            return expectedAccepted;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
