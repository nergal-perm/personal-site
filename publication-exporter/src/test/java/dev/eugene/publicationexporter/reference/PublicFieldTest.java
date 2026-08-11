package dev.eugene.publicationexporter.reference;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicFieldTest {

    @Test
    void ofFactoryBuildsTheExpectedField() {
        PublicField field = PublicField.of("title", "RU title");

        assertEquals("title", field.key());
        assertEquals("RU title", field.value());
    }

    @Test
    void equalFieldsBuiltSeparatelyAreEqual() {
        assertEquals(PublicField.of("title", "RU title"), PublicField.of("title", "RU title"));
        assertEquals(
                PublicField.of("title", "RU title").hashCode(),
                PublicField.of("title", "RU title").hashCode());
    }

    @Test
    void toStringShowsTheFieldData() {
        PublicField field = PublicField.of("title", "RU title");

        assertEquals("PublicField[key=title, value=RU title]", field.toString());
    }

    @Test
    void keyAndValueAreRejectedAtConstruction() {
        NullPointerException keyMissing = assertThrows(
                NullPointerException.class,
                () -> PublicField.of(null, "value"));
        assertEquals("key", keyMissing.getMessage());

        NullPointerException valueMissing = assertThrows(
                NullPointerException.class,
                () -> PublicField.of("key", null));
        assertEquals("value", valueMissing.getMessage());
    }

    @Test
    void valueFindsAFieldByKey() {
        assertEquals(
                Optional.of("RU description"),
                PublicField.value(
                        List.of(PublicField.of("title", "RU title"),
                                PublicField.of("description", "RU description")),
                        "description"));
        assertTrue(PublicField.value(List.of(PublicField.of("title", "RU title")), "missing").isEmpty());
    }
}
