package dev.eugene.publicationexporter.admission;

import java.util.List;

public final class EssayAdmissionFixtures {

    private EssayAdmissionFixtures() {
    }

    public static List<EssayAdmissionFixture> all() {
        return List.of(
                EssayAdmissionFixture.accepted("validEssay", """
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
                EssayAdmissionFixture.blocked("unpublished", """
                        ---
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("publish")),
                EssayAdmissionFixture.blocked("wrongCollection", """
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
                EssayAdmissionFixture.blocked("wrongContentType", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: claim
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("publicContentType")),
                EssayAdmissionFixture.blocked("invalidPublicId", """
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
                EssayAdmissionFixture.blocked("missingSourceId", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        """, List.of("id")),
                EssayAdmissionFixture.blocked("blankSourceId", """
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
                EssayAdmissionFixture.blocked("nullSourceId", """
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
                EssayAdmissionFixture.blocked("missingTitle", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        description: A valid description.
                        ---
                        """, List.of("title")),
                EssayAdmissionFixture.blocked("blankDescription", """
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
                EssayAdmissionFixture.blocked("missingPublicIdAndSourceId", """
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
