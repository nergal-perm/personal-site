package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.contract.KindContract;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlbumPublicationKindTest {

    private final AlbumPublicationKind admission = new AlbumPublicationKind();

    @Test
    void validAlbumOwnsMusicIdentityAndTranslatesRequiredFieldsOnly() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse("""
                ---
                publish: true
                publicCollection: music
                publicContentType: album
                publicId: kind-of-blue
                id: 4bc5-kind-of-blue
                title: Kind of Blue
                description: A landmark jazz album.
                artist: Miles Davis
                work: Kind of Blue
                context: A modal jazz record.
                association: Blue note.
                ---
                """));

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals(PublicationIdentity.of("music", "album", "kind-of-blue"), result.identity());
        assertEquals("music", admission.routePrefix());
        assertEquals(List.of(
                PublicField.of("title", "Kind of Blue"),
                PublicField.of("description", "A landmark jazz album."),
                PublicField.of("context", "A modal jazz record."),
                PublicField.of("association", "Blue note.")), result.fields());
        assertEquals("""
                artist: "Miles Davis"
                work: "Kind of Blue"
                reviewType: "album"
                """, result.structuredData());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.eugene.publicationexporter.admission.AlbumPublicationKindFixtures#all")
    void admitsOrBlocksPerSharedFixture(AlbumPublicationKindFixture fixture) {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(fixture.noteSource()));

        assertEquals(fixture.expectedAccepted(), result.accepted(), fixture.name());
        if (!fixture.expectedAccepted()) {
            assertEquals(List.of("listenFor"), blockedFields(result), fixture.name());
        }
    }

    @Test
    void validAlbumFlattensOptionalTranslatedFieldsInDeclaredOrder() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse("""
                ---
                publish: true
                publicCollection: music
                publicContentType: album
                publicId: kind-of-blue
                id: 4bc5-kind-of-blue
                title: Kind of Blue
                description: A landmark jazz album.
                artist: Miles Davis
                work: Kind of Blue
                context: A modal jazz record.
                association: Blue note.
                format: LP
                care: Listen with headphones.
                listenFor:
                  - modal harmony
                  - ensemble interaction
                genreTags:
                  - jazz
                  - modal
                releaseDate: 1959-08-17
                streamingUrl: "https://example.test/kind-of-blue"
                bandcampEmbedUrl: "https://bandcamp.test/embed/kind-of-blue"
                ---
                """));

        assertTrue(result.accepted(), result.diagnostics().toString());
        assertEquals(List.of(
                PublicField.of("title", "Kind of Blue"),
                PublicField.of("description", "A landmark jazz album."),
                PublicField.of("context", "A modal jazz record."),
                PublicField.of("association", "Blue note."),
                PublicField.of("format", "LP"),
                PublicField.of("care", "Listen with headphones."),
                PublicField.of("listenFor[0]", "modal harmony"),
                PublicField.of("listenFor[1]", "ensemble interaction")), result.fields());
        assertEquals("""
                artist: "Miles Davis"
                work: "Kind of Blue"
                releaseDate: "1959-08-17"
                streamingUrl: "https://example.test/kind-of-blue"
                bandcampEmbedUrl: "https://bandcamp.test/embed/kind-of-blue"
                genreTags:
                  - "jazz"
                  - "modal"
                reviewType: "album"
                """, result.structuredData());
    }

    @ParameterizedTest(name = "missing {0}")
    @MethodSource("requiredAlbumFields")
    void missingRequiredAlbumFieldBlocksAdmission(String field) {
        String note = validAlbumWith("omit: " + field)
                .replace("\n" + field + ": " + fieldValue(field), "");

        AdmittedPublication result = admission.admit(MarkdownNote.parse(note));

        assertEquals(List.of(field), blockedFields(result));
        assertTrue(result.diagnostics().get(0).message().contains(field));
    }

    @ParameterizedTest(name = "blank {0}")
    @MethodSource("requiredAlbumFields")
    void blankRequiredAlbumFieldBlocksAdmission(String field) {
        String note = validAlbumWith(field + ": \"\"")
                .replace("\n" + field + ": " + fieldValue(field), "");

        AdmittedPublication result = admission.admit(MarkdownNote.parse(note));

        assertEquals(List.of(field), blockedFields(result));
        assertTrue(result.diagnostics().get(0).message().contains(field));
    }

    @Test
    void explicitEmptyListenForAndGenreTagsListsAreAccepted() {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(
                validAlbumWith("listenFor: []\ngenreTags: []")));

        assertTrue(result.accepted(), result.diagnostics().toString());
    }

    @ParameterizedTest(name = "blank entry in {0}")
    @MethodSource("scalarListFields")
    void blankScalarListEntryBlocksAdmission(String field) {
        AdmittedPublication result = admission.admit(MarkdownNote.parse(
                validAlbumWith(field + ":\n  - \"   \"")));

        assertEquals(List.of(field), blockedFields(result));
        assertTrue(result.diagnostics().get(0).message().contains(field));
    }

    @ParameterizedTest(name = "malformed {0}")
    @MethodSource("malformedScalarLists")
    void malformedScalarListBlocksAdmission(String fieldAndValue) {
        String field = fieldAndValue.substring(0, fieldAndValue.indexOf(':'));
        AdmittedPublication result = admission.admit(MarkdownNote.parse(
                validAlbumWith(fieldAndValue)));

        assertEquals(List.of(field), blockedFields(result));
        assertTrue(result.diagnostics().get(0).message().contains(field));
    }

    @Test
    void albumContractDescribesRequiredAndOptionalFields() {
        KindContract contract = admission.contract();

        assertEquals(List.of("publish", "publicCollection", "publicContentType", "publicId", "id", "title",
                "description", "artist", "work", "context", "association"),
                contract.requiredFields().stream().map(field -> field.name()).toList());
        assertEquals(List.of("format", "care", "releaseDate", "streamingUrl", "bandcampEmbedUrl", "listenFor",
                "genreTags"), contract.optionalFields().stream().map(field -> field.name()).toList());
    }

    @Test
    void albumIsRegisteredAsAnInstalledKind() {
        assertTrue(PublicationKinds.installed().forIdentity("music", "album").isPresent());
    }

    private static Stream<String> requiredAlbumFields() {
        return Stream.of("artist", "work", "context", "association");
    }

    private static Stream<String> malformedScalarLists() {
        return Stream.of(
                "listenFor: one item",
                "genreTags: one tag",
                "listenFor:\n  - nested: object",
                "genreTags:\n  - nested: object");
    }

    private static Stream<String> scalarListFields() {
        return Stream.of("listenFor", "genreTags");
    }

    private static String validAlbumWith(String additionalField) {
        return """
                ---
                publish: true
                publicCollection: music
                publicContentType: album
                publicId: valid-album
                id: 4bc5-valid-album
                title: Valid album
                description: A valid public album.
                artist: Valid artist
                work: Valid work
                context: Valid context.
                association: Valid association.
                """ + additionalField + "\n---\n";
    }

    private static String fieldValue(String field) {
        return switch (field) {
            case "artist" -> "Valid artist";
            case "work" -> "Valid work";
            case "context" -> "Valid context.";
            case "association" -> "Valid association.";
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static List<String> blockedFields(AdmittedPublication result) {
        return result.diagnostics().stream().map(Diagnostic::field).toList();
    }
}
