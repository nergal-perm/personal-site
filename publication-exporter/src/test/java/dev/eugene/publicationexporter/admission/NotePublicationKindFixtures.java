package dev.eugene.publicationexporter.admission;

import java.util.List;

public final class NotePublicationKindFixtures {

    private NotePublicationKindFixtures() {
    }

    public static List<NotePublicationKindFixture> all() {
        return List.of(
                NotePublicationKindFixture.accepted("validNote", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: note
                        publicId: my-note
                        id: 8f2c-my-note
                        title: My Note
                        description: A valid description.
                        ---
                        """),
                NotePublicationKindFixture.blocked("invalidPublicId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: note
                        publicId: My_Note
                        id: 8f2c-my-note
                        title: My Note
                        description: A valid description.
                        ---
                        """, List.of("publicId")),
                NotePublicationKindFixture.blocked("missingSourceId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: note
                        publicId: my-note
                        title: My Note
                        description: A valid description.
                        ---
                        """, List.of("id")),
                NotePublicationKindFixture.blocked("missingTitle", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: note
                        publicId: my-note
                        id: 8f2c-my-note
                        description: A valid description.
                        ---
                        """, List.of("title")),
                NotePublicationKindFixture.blocked("blankDescription", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: note
                        publicId: my-note
                        id: 8f2c-my-note
                        title: My Note
                        description: "   "
                        ---
                        """, List.of("description")),
                NotePublicationKindFixture.blocked("missingPublicIdAndSourceId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: note
                        title: My Note
                        description: A valid description.
                        ---
                        """, List.of("publicId", "id")));
    }
}
