package dev.eugene.publicationexporter.admission;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AboutPageBodyTest {

    private static final String VALID_BODY = """
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
            """;

    @Test
    void parsesEveryRequiredSection() {
        AboutPageBody parsed = AboutPageBody.parse(VALID_BODY);
        assertEquals("Кратко.", parsed.summary());
        assertEquals("Бровь.", parsed.eyebrow());
        assertEquals("Лид.", parsed.lead());
        assertEquals("Колофон.", parsed.colophon());
        assertEquals(List.of(new AboutPageBody.Principle("Первый", "Принцип.")), parsed.principles());
    }

    @Test
    void missingSectionIsRejected() {
        String withoutColophon = """
                ## Кратко

                Кратко.

                ## Eyebrow

                Бровь.

                ## Лид

                Лид.

                ## Принципы

                ### Первый

                Принцип.
                """;
        assertThrows(AboutPageBody.MalformedBodyException.class, () -> AboutPageBody.parse(withoutColophon));
    }

    @Test
    void principlesSectionWithNoSubsectionsIsRejected() {
        String withoutPrinciples = """
                ## Кратко

                Кратко.

                ## Eyebrow

                Бровь.

                ## Лид

                Лид.

                ## Принципы

                ## Колофон

                Колофон.
                """;
        assertThrows(AboutPageBody.MalformedBodyException.class, () -> AboutPageBody.parse(withoutPrinciples));
    }

    @Test
    void multiplePrinciplesParseInOrder() {
        String twoPrinciples = """
                ## Кратко

                Кратко.

                ## Eyebrow

                Бровь.

                ## Лид

                Лид.

                ## Принципы

                ### Первый

                Один.

                ### Второй

                Два.

                ## Колофон

                Колофон.
                """;
        assertEquals(
                List.of(new AboutPageBody.Principle("Первый", "Один."), new AboutPageBody.Principle("Второй", "Два.")),
                AboutPageBody.parse(twoPrinciples).principles());
    }
}
