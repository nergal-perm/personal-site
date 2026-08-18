package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicNoteIndexTest {

    @Test
    void sourceIdForReturnsTheAdmittedSourceId() {
        PublicNoteIndex index = PublicNoteIndex.from(vaultReaderWithOneAdmittedNote(), noteIntake());

        assertEquals(Optional.of("vault-source-id-target"), index.sourceIdFor("Target"));
        PublicNoteIndex.NoteReference reference = index.referenceFor("Target").orElseThrow();
        assertEquals("/essays/target/", reference.route());
        assertEquals("vault-source-id-target", reference.sourceId());
    }

    @Test
    void sourceIdForIsAbsentForAnAmbiguousStem() {
        PublicNoteIndex index = PublicNoteIndex.from(vaultReaderWithTwoNotesSharingAStem(), noteIntake());

        assertEquals(Optional.empty(), index.routeFor("Target"));
        assertEquals(Optional.empty(), index.sourceIdFor("Target"));
    }

    @Test
    void lookupsAreAbsentWhenNoCandidatesAreAdmitted() {
        PublicNoteIndex index = PublicNoteIndex.from(vaultReaderWithNoAdmittedNotes(), noteIntake());

        assertEquals(Optional.empty(), index.routeFor("Unpublished"));
        assertEquals(Optional.empty(), index.sourceIdFor("Unpublished"));
    }

    private static VaultReader vaultReaderWithOneAdmittedNote() {
        VaultRelativePath notePath = VaultRelativePath.of("essay/Target.md");
        return VaultReader.createNull(Map.of(notePath, """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: target
                id: vault-source-id-target
                title: Target
                description: A test note with a stable source id.
                ---
                # Target

                Target body.
                """));
    }

    private static VaultReader vaultReaderWithTwoNotesSharingAStem() {
        VaultRelativePath first = VaultRelativePath.of("notes/Target.md");
        VaultRelativePath second = VaultRelativePath.of("archive/Target.md");
        return VaultReader.createNull(Map.of(
                first, """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: first-target
                        id: source-id-first
                        title: First Target
                        description: First public test note.
                        ---
                        # First Target

                        First target body.
                        """,
                second, """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: second-target
                        id: source-id-second
                        title: Second Target
                        description: Second public test note.
                        ---
                        # Second Target

                        Second target body.
                        """));
    }

    private static VaultReader vaultReaderWithNoAdmittedNotes() {
        VaultRelativePath notePath = VaultRelativePath.of("notes/Unpublished.md");
        return VaultReader.createNull(Map.of(notePath, """
                ---
                publicCollection: blog
                publicContentType: essay
                publicId: unpublished
                id: source-id-unpublished
                title: Unpublished
                description: This note is not published.
                ---
                # Unpublished

                Unpublished body.
                """));
    }

    private static NoteIntake noteIntake() {
        return new NoteIntake(PublicationKinds.installed());
    }
}
