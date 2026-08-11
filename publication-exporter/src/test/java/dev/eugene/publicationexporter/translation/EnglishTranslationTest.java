package dev.eugene.publicationexporter.translation;

import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnglishTranslationTest {

    @Test
    void equalTranslationsHaveValueObjectSemantics() {
        EnglishTranslation first = EnglishTranslation.of(
                "English body", List.of(PublicField.of("title", "English title")));
        EnglishTranslation second = EnglishTranslation.of(
                "English body", List.of(PublicField.of("title", "English title")));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(
                "EnglishTranslation[body=English body, fields=[PublicField[key=title, value=English title]]]",
                first.toString());
    }
}
