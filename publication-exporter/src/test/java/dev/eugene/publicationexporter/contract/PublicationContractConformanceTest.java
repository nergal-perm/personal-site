package dev.eugene.publicationexporter.contract;

import dev.eugene.publicationexporter.admission.BookPublicationKindFixture;
import dev.eugene.publicationexporter.admission.BookPublicationKindFixtures;
import dev.eugene.publicationexporter.admission.AlbumPublicationKindFixture;
import dev.eugene.publicationexporter.admission.AlbumPublicationKindFixtures;
import dev.eugene.publicationexporter.admission.ConceptPublicationKindFixtures;
import dev.eugene.publicationexporter.admission.ConceptPublicationKindFixture;
import dev.eugene.publicationexporter.admission.ClaimPublicationKindFixture;
import dev.eugene.publicationexporter.admission.ClaimPublicationKindFixtures;
import dev.eugene.publicationexporter.admission.CuratedPagePublicationKindFixture;
import dev.eugene.publicationexporter.admission.CuratedPagePublicationKindFixtures;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @ParameterizedTest(name = "music/album {0}")
    @MethodSource("allAlbumAdmissionFixtures")
    void albumContractVerdictAgreesWithFixtureAndRuntimeValidator(AlbumPublicationKindFixture fixture) {
        MarkdownNote note = MarkdownNote.parse(fixture.noteSource());
        KindContract albumKind = new PublicationContractWriter().write().kinds().stream()
                .filter(kind -> kind.collection().equals("music") && kind.contentType().equals("album"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "publicCollection/publicContentType is not a supported publication kind"));

        boolean contractAccepts = contractAccepts(albumKind, note);
        VaultRelativePath path = VaultRelativePath.of("music/" + fixture.name() + ".md");
        boolean runtimeAccepts = intake.admit(path, VaultReader.createNull(Map.of(path, fixture.noteSource())))
                .accepted();

        assertEquals(fixture.expectedAccepted(), contractAccepts, "contract verdict for " + fixture.name());
        assertEquals(fixture.expectedAccepted(), runtimeAccepts, "runtime verdict for " + fixture.name());
        assertEquals(contractAccepts, runtimeAccepts, "contract/runtime agreement for " + fixture.name());
    }

    @ParameterizedTest(name = "editorial/curated_page {0}")
    @MethodSource("allCuratedPageAdmissionFixtures")
    void curatedPageContractVerdictAgreesWithFixtureAndRuntimeValidator(
            CuratedPagePublicationKindFixture fixture) {
        MarkdownNote note = MarkdownNote.parse(fixture.noteSource());
        KindContract curatedPageKind = new PublicationContractWriter().write().kinds().stream()
                .filter(kind -> kind.collection().equals("editorial")
                        && kind.contentType().equals("curated_page"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "publicCollection/publicContentType is not a supported publication kind"));

        boolean contractAccepts = contractAccepts(curatedPageKind, note);
        VaultRelativePath path = VaultRelativePath.of("editorial/" + fixture.name() + ".md");
        boolean runtimeAccepts = intake.admit(path, VaultReader.createNull(Map.of(path, fixture.noteSource())))
                .accepted();

        assertEquals(fixture.expectedAccepted(), runtimeAccepts, "runtime verdict for " + fixture.name());
        assertTrue(contractAccepts || !runtimeAccepts,
                "an accepted runtime fixture must satisfy the represented contract fields: " + fixture.name());
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
        return ConceptPublicationKindFixtures.all().stream();
    }

    private static Stream<AlbumPublicationKindFixture> allAlbumAdmissionFixtures() {
        return AlbumPublicationKindFixtures.all().stream();
    }

    private static Stream<CuratedPagePublicationKindFixture> allCuratedPageAdmissionFixtures() {
        return CuratedPagePublicationKindFixtures.all().stream();
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
            if (optionalFieldPresent(field, note) && !fieldSatisfied(field, note)) {
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

    private boolean optionalFieldPresent(FieldContract field, MarkdownNote note) {
        if (field.type() == FieldContract.Type.STRING_LIST
                || field.type() == FieldContract.Type.STRUCTURED_LIST) {
            return optionalListFieldPresent(field.name(), note);
        }
        return fieldPresent(field.name(), note);
    }

    private boolean optionalListFieldPresent(String key, MarkdownNote note) {
        MarkdownNote.StructuredField shape = note.structuredField(key);
        return shape == MarkdownNote.StructuredField.POPULATED_LIST
                || shape == MarkdownNote.StructuredField.NON_LIST;
    }

    private boolean fieldPresent(String key, MarkdownNote note) {
        return note.string(key).isPresent()
                || note.structuredField(key) != MarkdownNote.StructuredField.ABSENT
                || !note.listOfScalars(key).isEmpty();
    }

    private boolean fieldSatisfied(FieldContract field, MarkdownNote note) {
        return switch (field.type()) {
            case BOOLEAN -> booleanFieldSatisfied(field, note);
            case STRING -> stringFieldSatisfied(field, note.string(field.name()).orElse(null));
            case STRING_LIST -> stringListFieldSatisfied(field, note.listOfScalars(field.name()));
            case STRUCTURED_LIST -> structuredListFieldSatisfied(field, note.listOfMaps(field.name()));
        };
    }

    private boolean booleanFieldSatisfied(FieldContract field, MarkdownNote note) {
        if (field.allowedValues() == null) {
            return note.booleanValue(field.name()).isPresent();
        }
        return field.allowedValues().contains(String.valueOf(note.flag(field.name())));
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
        return hasStructuredListValues(values)
                && hasDeclaredStructuredMembers(field)
                && values.stream().allMatch(value -> hasRequiredMembers(field, value) && hasExactMembers(field, value));
    }

    private boolean hasStructuredListValues(List<Map<String, String>> values) {
        return !values.isEmpty();
    }

    private boolean hasDeclaredStructuredMembers(FieldContract field) {
        return field.structuredMembers() != null && !field.structuredMembers().isEmpty();
    }

    private boolean hasRequiredMembers(FieldContract field, Map<String, String> value) {
        for (String member : field.structuredMembers()) {
            String memberValue = value.get(member);
            if (memberValue == null || memberValue.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasExactMembers(FieldContract field, Map<String, String> value) {
        return value.keySet().containsAll(field.structuredMembers())
                && field.structuredMembers().containsAll(value.keySet());
    }
}
