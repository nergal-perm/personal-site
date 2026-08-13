package dev.eugene.publicationexporter.admission;

import java.util.List;

public final class CuratedPagePublicationKindFixtures {

    private CuratedPagePublicationKindFixtures() {
    }

    public static List<CuratedPagePublicationKindFixture> all() {
        return List.of(
                CuratedPagePublicationKindFixture.accepted("validAboutPage", """
                        ---
                        publish: true
                        publicCollection: editorial
                        publicContentType: curated_page
                        publicId: about
                        editorialPage: about
                        id: source-about
                        title: About
                        ---
                        ## Кратко

                        Кратко.

                        ## Eyebrow

                        Бровь.

                        ## Лид

                        Лид.

                        ## Принципы

                        ### Первый

                        Принцип.

                        ## Колофон

                        Колофон.
                        """),
                CuratedPagePublicationKindFixture.accepted("aboutPageWithSearchableAndTwoPrinciples", """
                        ---
                        publish: true
                        publicCollection: editorial
                        publicContentType: curated_page
                        publicId: about
                        editorialPage: about
                        id: source-about-searchable
                        title: About Searchable
                        publicSearchable: true
                        ---
                        ## Кратко

                        Кратко.

                        ## Eyebrow

                        Бровь.

                        ## Лид

                        Лид.

                        ## Принципы

                        ### Первый

                        Первый принцип.

                        ### Второй

                        Второй принцип.

                        ## Колофон

                        Колофон.
                        """),
                CuratedPagePublicationKindFixture.blocked("publicSearchableIsNotBoolean", """
                        ---
                        publish: true
                        publicCollection: editorial
                        publicContentType: curated_page
                        publicId: about
                        editorialPage: about
                        id: source-about-invalid-searchable
                        title: About Invalid Searchable
                        publicSearchable: not-a-boolean
                        ---
                        ## Кратко

                        Кратко.

                        ## Eyebrow

                        Бровь.

                        ## Лид

                        Лид.

                        ## Принципы

                        ### Первый

                        Принцип.

                        ## Колофон

                        Колофон.
                        """),
                CuratedPagePublicationKindFixture.blocked("knownUnsupportedPageKey", """
                        ---
                        publish: true
                        publicCollection: editorial
                        publicContentType: curated_page
                        publicId: now
                        editorialPage: now
                        id: source-now
                        title: Now
                        ---
                        ## Кратко

                        Кратко.

                        ## Eyebrow

                        Бровь.

                        ## Лид

                        Лид.

                        ## Принципы

                        ### Первый

                        Принцип.

                        ## Колофон

                        Колофон.
                        """),
                CuratedPagePublicationKindFixture.blocked("publicIdDoesNotMatchEditorialPage", """
                        ---
                        publish: true
                        publicCollection: editorial
                        publicContentType: curated_page
                        publicId: different
                        editorialPage: about
                        id: source-different
                        title: Different
                        ---
                        ## Кратко

                        Кратко.

                        ## Eyebrow

                        Бровь.

                        ## Лид

                        Лид.

                        ## Принципы

                        ### Первый

                        Принцип.

                        ## Колофон

                        Колофон.
                        """),
                CuratedPagePublicationKindFixture.blocked("missingColophon", """
                        ---
                        publish: true
                        publicCollection: editorial
                        publicContentType: curated_page
                        publicId: about
                        editorialPage: about
                        id: source-about-incomplete
                        title: Incomplete About
                        ---
                        ## Кратко

                        Кратко.

                        ## Eyebrow

                        Бровь.

                        ## Лид

                        Лид.

                        ## Принципы

                        ### Первый

                        Принцип.
                        """));
    }
}
