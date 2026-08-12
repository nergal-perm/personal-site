package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConceptPublicationKindTest {

    private final ConceptPublicationKind admission = new ConceptPublicationKind();

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.eugene.publicationexporter.admission.ConceptPublicationKindFixtures#all")
    void admitsOrBlocksPerSharedFixture(ConceptPublicationKindFixture fixture) {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(fixture.noteSource()));

        assertEquals(fixture.expectedAccepted(), result.accepted(), fixture.name());
        if (!fixture.expectedAccepted()) {
            assertEquals(List.of("relations"), blockedFields(result), fixture.name());
        }
    }

    @Test
    void validConceptOwnsConceptIdentityAndRoute() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(
                ConceptPublicationKindFixtures.all().get(0).noteSource()));

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals(PublicationIdentity.of("concepts", "concept", "concept-example"), result.identity());
        assertEquals("4bc5-concept-example", result.sourceId());
        assertEquals("concepts", admission.routePrefix());
        assertEquals(List.of(
                PublicField.of("title", "Core Concept"),
                PublicField.of("description", "A valid public concept.")), result.fields());
    }

    @Test
    void validConceptFlattensOptionalFieldsInDeclaredOrder() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse("""
                ---
                publish: true
                publicCollection: concepts
                publicContentType: concept
                publicId: concept-with-all-fields
                id: 4bc5-concept-all-fields
                title: Concept with all fields
                description: A valid public concept with all translated fields.
                notThis: Not a neighboring concept.
                relations:
                  - name: parent
                    relation: implies
                  - name: sibling
                    relation: relates
                examples:
                  - a first example
                  - a second example
                ---
                """));

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals(List.of(
                PublicField.of("title", "Concept with all fields"),
                PublicField.of("description", "A valid public concept with all translated fields."),
                PublicField.of("notThis", "Not a neighboring concept."),
                PublicField.of("relations[0].name", "parent"),
                PublicField.of("relations[0].relation", "implies"),
                PublicField.of("relations[1].name", "sibling"),
                PublicField.of("relations[1].relation", "relates"),
                PublicField.of("examples[0]", "a first example"),
                PublicField.of("examples[1]", "a second example")), result.fields());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedRelations")
    void malformedRelationsBlockAdmissionWithRelationsDiagnostic(String relationMetadata) {
        AdmittedPublication result = admission.admit(MarkdownNote.parse("""
                ---
                publish: true
                publicCollection: concepts
                publicContentType: concept
                publicId: malformed-relations
                id: 4bc5-malformed-relations
                title: Malformed relations
                description: A concept with malformed relations.
                relations:
                """ + relationMetadata + """
                ---
                """));

        assertEquals(List.of("relations"), blockedFields(result));
        assertTrue(result.diagnostics().get(0).message().contains("relations"));
    }

    @Test
    void nonListRelationsBlockAdmission() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(validConceptWith("relations: parent")));

        assertEquals(List.of("relations"), blockedFields(result));
    }

    @Test
    void nonListExamplesBlockAdmission() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(validConceptWith("examples: one example")));

        assertEquals(List.of("examples"), blockedFields(result));
    }

    @Test
    void blankExamplesBlockAdmission() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(validConceptWith("examples:\n  - \"   \"")));

        assertEquals(List.of("examples"), blockedFields(result));
    }

    @ParameterizedTest(name = "missing {0}")
    @MethodSource("requiredFields")
    void missingRequiredFieldBlocksAdmission(String field) {
        String note = validConceptWith("omit: " + field).replace("\n" + field + ": " + fieldValue(field), "");

        AdmittedPublication result = admission.admit(MarkdownNote.parse(note));

        assertEquals(List.of(field), blockedFields(result));
    }

    private static Stream<String> malformedRelations() {
        return Stream.of(
                "  - name: parent\n",
                "  - name: parent\n    relation: implies\n    privateNote: forbidden\n");
    }

    private static Stream<String> requiredFields() {
        return Stream.of("id", "title", "description");
    }

    private static String validConceptWith(String additionalField) {
        return """
                ---
                publish: true
                publicCollection: concepts
                publicContentType: concept
                publicId: valid-concept
                id: 4bc5-valid-concept
                title: Valid concept
                description: A valid public concept.
                """ + additionalField + "\n---\n";
    }

    private static String fieldValue(String field) {
        return switch (field) {
            case "id" -> "4bc5-valid-concept";
            case "title" -> "Valid concept";
            case "description" -> "A valid public concept.";
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static List<String> blockedFields(AdmittedPublication result) {
        return result.diagnostics().stream().map(Diagnostic::field).toList();
    }
}
