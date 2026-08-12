package dev.eugene.publicationexporter.admission;

import java.util.List;

public final class AlbumPublicationKindFixtures {

    private AlbumPublicationKindFixtures() {
    }

    public static List<AlbumPublicationKindFixture> all() {
        return List.of(
                AlbumPublicationKindFixture.accepted("validAlbumWithRequiredFields", """
                        ---
                        publish: true
                        publicCollection: music
                        publicContentType: album
                        publicId: album-example
                        id: 4bc5-album-example
                        title: Album Example
                        description: A valid public album.
                        artist: Album Artist
                        work: Album Work
                        context: Album context.
                        association: Album association.
                        ---
                        """),
                AlbumPublicationKindFixture.accepted("albumWithEveryOptionalField", """
                        ---
                        publish: true
                        publicCollection: music
                        publicContentType: album
                        publicId: album-with-all-fields
                        id: 4bc5-album-all-fields
                        title: Album with all fields
                        description: A valid public album with every optional field.
                        artist: Album Artist
                        work: Album Work
                        context: Album context.
                        association: Album association.
                        format: Digital
                        care: Play it loud.
                        listenFor:
                          - melody
                          - texture
                        releaseDate: 2024-04-01
                        genreTags:
                          - ambient
                          - electronic
                        streamingUrl: "https://example.test/album"
                        bandcampEmbedUrl: "https://bandcamp.test/embed/album"
                        ---
                        """),
                AlbumPublicationKindFixture.blocked("albumWithScalarListenFor", """
                        ---
                        publish: true
                        publicCollection: music
                        publicContentType: album
                        publicId: album-bad-listen-for
                        id: 4bc5-album-bad-listen-for
                        title: Album with Bad Listen For
                        description: An album with malformed translated list data.
                        artist: Album Artist
                        work: Album Work
                        context: Album context.
                        association: Album association.
                        listenFor: melody
                        ---
                        """));
    }
}
