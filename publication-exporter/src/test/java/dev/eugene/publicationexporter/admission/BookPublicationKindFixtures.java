package dev.eugene.publicationexporter.admission;

import java.util.List;

public final class BookPublicationKindFixtures {

    private BookPublicationKindFixtures() {
    }

    public static List<BookPublicationKindFixture> all() {
        return List.of(
                BookPublicationKindFixture.accepted("validBook", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - Eric Ries
                        ---
                        """),
                BookPublicationKindFixture.blocked("missingAuthors", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("emptyAuthors", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors: []
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("blankAuthor", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - "   "
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("nonStringAuthor", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - name: Eric Ries
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("numericAuthor", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - 123
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("flowSequenceAuthor", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - []
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("flowMapAuthor", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - {}
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("scientificAuthor", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - 1e3
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("hexAuthor", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - 0x10
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("uppercaseBooleanAuthor", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - TRUE
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("dateAuthor", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - 2026-01-01
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("leadingZeroAuthor", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - 0123
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("doubleZeroAuthor", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - 00
                        ---
                        """, List.of("authors")),
                BookPublicationKindFixture.blocked("blankUse", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - Eric Ries
                        use:
                        ---
                        """, List.of("use")),
                BookPublicationKindFixture.blocked("blankPublication", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - Eric Ries
                        publication:
                        ---
                        """, List.of("publication")),
                BookPublicationKindFixture.blocked("structuredBoundary", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - Eric Ries
                        boundary:
                          kind: note
                        ---
                        """, List.of("boundary")),
                BookPublicationKindFixture.blocked("selectedQuote", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - Eric Ries
                        selectedQuote:
                          kind: text
                          text: A quote that needs translation.
                        ---
                        """, List.of("selectedQuote")),
                BookPublicationKindFixture.blocked("quotedSelectedQuote", """
                        ---
                        publish: true
                        publicCollection: bibliography
                        publicContentType: book
                        publicId: the-lean-startup
                        id: 8f2c-the-lean-startup
                        title: The Lean Startup
                        description: A valid description.
                        authors:
                          - Eric Ries
                        'selectedQuote':
                          kind: text
                          text: A quote that needs translation.
                        ---
                        """, List.of("selectedQuote")));
    }
}
