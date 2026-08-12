package dev.eugene.publicationexporter.admission;

import java.util.List;

public final class ConceptPublicationKindFixtures {

    private ConceptPublicationKindFixtures() {
    }

    public static List<ConceptPublicationKindFixture> all() {
        return List.of(
                ConceptPublicationKindFixture.accepted("validConceptWithoutRelations", """
                        ---
                        publish: true
                        publicCollection: concepts
                        publicContentType: concept
                        publicId: concept-example
                        id: 4bc5-concept-example
                        title: Core Concept
                        description: A valid public concept.
                        ---
                        """),
                ConceptPublicationKindFixture.accepted("conceptWithExplicitEmptyRelationsAndExamples", """
                        ---
                        publish: true
                        publicCollection: concepts
                        publicContentType: concept
                        publicId: concept-with-empty-lists
                        id: 4bc5-concept-empty-lists
                        title: Concept with Empty Lists
                        description: A valid public concept with explicit empty lists.
                        relations: []
                        examples: []
                        ---
                        """),
                ConceptPublicationKindFixture.accepted("conceptWithRelationsAndExamples", """
                        ---
                        publish: true
                        publicCollection: concepts
                        publicContentType: concept
                        publicId: concept-with-relations
                        id: 4bc5-concept-relations
                        title: Concept with Relations
                        description: A valid public concept with relation data.
                        relations:
                          - name: parent
                            relation: implies
                        examples:
                          - a first relation example
                          - a second relation example
                        ---
                        """),
                ConceptPublicationKindFixture.blocked("conceptWithMalformedRelations", """
                        ---
                        publish: true
                        publicCollection: concepts
                        publicContentType: concept
                        publicId: concept-bad-relations
                        id: 4bc5-concept-bad-relations
                        title: Concept with Malformed Relations
                        description: A concept with malformed relation entries.
                        relations:
                          - relation: implies
                        ---
                        """));
    }
}
