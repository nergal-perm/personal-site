package dev.eugene.publicationexporter.admission;

import java.util.List;

public final class ClaimPublicationKindFixtures {

    private ClaimPublicationKindFixtures() {
    }

    public static List<ClaimPublicationKindFixture> all() {
        return List.of(
                ClaimPublicationKindFixture.accepted("validClaim", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: claim
                        publicId: latency-budget-is-fiction
                        id: 91aa-latency-claim
                        title: A fixed latency budget is fiction
                        description: A valid description.
                        statement: A fixed latency budget is usually the wrong abstraction.
                        ---
                        Claim body.
                        """),
                ClaimPublicationKindFixture.blocked("missingStatement", """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: claim
                        publicId: missing-statement
                        id: 91aa-missing-statement
                        title: Missing statement
                        description: A valid description.
                        ---
                        """, List.of("statement")));
    }
}
