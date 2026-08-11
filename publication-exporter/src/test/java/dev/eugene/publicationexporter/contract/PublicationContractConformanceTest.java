package dev.eugene.publicationexporter.contract;

import dev.eugene.publicationexporter.admission.EssayPublicationKind;
import dev.eugene.publicationexporter.admission.EssayPublicationKindFixture;
import dev.eugene.publicationexporter.admission.EssayPublicationKindFixtures;
import dev.eugene.publicationexporter.note.MarkdownNote;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicationContractConformanceTest {

    private final EssayPublicationKind admission = new EssayPublicationKind();

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.eugene.publicationexporter.admission.EssayPublicationKindFixtures#all")
    void contractVerdictAgreesWithFixtureAndRuntimeValidator(EssayPublicationKindFixture fixture) {
        MarkdownNote note = MarkdownNote.parse(fixture.noteSource());
        KindContract essayKind = new PublicationContractWriter().write().kinds().get(0);

        boolean contractAccepts = contractAccepts(essayKind, note);
        boolean runtimeAccepts = admission.admit(note).accepted();

        assertEquals(fixture.expectedAccepted(), contractAccepts, "contract verdict for " + fixture.name());
        assertEquals(fixture.expectedAccepted(), runtimeAccepts, "runtime verdict for " + fixture.name());
        assertEquals(contractAccepts, runtimeAccepts, "contract/runtime agreement for " + fixture.name());
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
