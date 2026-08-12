package dev.eugene.publicationexporter.contract;

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
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    private static Stream<NotePublicationKindFixture> allNoteAdmissionFixtures() {
        return NotePublicationKindFixtures.all().stream();
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
        return true;
    }

    private boolean fieldSatisfied(FieldContract field, MarkdownNote note) {
        if (field.type() == FieldContract.Type.BOOLEAN) {
            return field.allowedValues().contains(String.valueOf(note.flag(field.name())));
        }
        return note.string(field.name()).map(value -> stringFieldSatisfied(field, value)).orElse(false);
    }

    private boolean stringFieldSatisfied(FieldContract field, String value) {
        if (field.nonBlank()) {
            return !value.isBlank();
        }
        if (field.pattern() != null) {
            return Pattern.compile(field.pattern()).matcher(value).matches();
        }
        return field.allowedValues().contains(value);
    }
}
