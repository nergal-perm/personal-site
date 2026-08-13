package dev.eugene.publicationexporter.admission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @ParameterizedTest
    @ValueSource(strings = {"Кратко", "Eyebrow", "Лид", "Принципы", "Колофон"})
    void duplicateRequiredHeadingIsRejected(String heading) {
        AboutPageBody.MalformedBodyException error = assertThrows(
                AboutPageBody.MalformedBodyException.class,
                () -> AboutPageBody.parse(VALID_BODY + "\n## " + heading + "\n\nПовтор."));

        assertTrue(error.getMessage().contains("## " + heading));
        assertTrue(error.getMessage().contains("Duplicate"));
    }

    @Test
    void preservesParagraphBreaksInEveryProseValue() {
        AboutPageBody parsed = AboutPageBody.parse("""
                ## Кратко

                Кратко первая строка.
                Продолжение кратко.

                Второй абзац кратко.

                ## Eyebrow

                Бровь первая строка.

                Второй абзац брови.

                ## Лид

                Лид первая строка.

                Второй абзац лида.

                ## Принципы

                ### Первый

                Первый принцип первая строка.

                Второй абзац первого принципа.

                ### Второй

                Второй принцип первая строка.

                Второй абзац второго принципа.

                ## Колофон

                Колофон первая строка.

                Второй абзац колофона.
                """);

        assertEquals("Кратко первая строка. Продолжение кратко.\n\nВторой абзац кратко.", parsed.summary());
        assertEquals("Бровь первая строка.\n\nВторой абзац брови.", parsed.eyebrow());
        assertEquals("Лид первая строка.\n\nВторой абзац лида.", parsed.lead());
        assertEquals("Первый принцип первая строка.\n\nВторой абзац первого принципа.",
                parsed.principles().get(0).text());
        assertEquals("Второй принцип первая строка.\n\nВторой абзац второго принципа.",
                parsed.principles().get(1).text());
        assertEquals("Колофон первая строка.\n\nВторой абзац колофона.", parsed.colophon());
    }
}
