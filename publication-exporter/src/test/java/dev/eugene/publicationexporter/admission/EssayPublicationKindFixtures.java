package dev.eugene.publicationexporter.admission;

import java.util.List;

public final class EssayPublicationKindFixtures {

    private EssayPublicationKindFixtures() {
    }

    public static List<EssayPublicationKindFixture> all() {
        return List.of(
                EssayPublicationKindFixture.accepted("validEssay", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """),
                EssayPublicationKindFixture.blocked("invalidPublicId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: My_Essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("publicId")),
                EssayPublicationKindFixture.blocked("missingSourceId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("id")),
                EssayPublicationKindFixture.blocked("blankSourceId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: "   "
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("id")),
                EssayPublicationKindFixture.blocked("nullSourceId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: null
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("id")),
                EssayPublicationKindFixture.blocked("missingTitle", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        description: A valid description.
                        ---
                        """, List.of("title")),
                EssayPublicationKindFixture.blocked("blankDescription", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: "   "
                        ---
                        """, List.of("description")),
                EssayPublicationKindFixture.blocked("missingPublicIdAndSourceId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("publicId", "id")));
    }
}
