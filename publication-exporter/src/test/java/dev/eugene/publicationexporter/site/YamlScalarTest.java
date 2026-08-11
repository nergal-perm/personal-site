package dev.eugene.publicationexporter.site;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YamlScalarTest {

    @Test
    void escapesDoubleQuotes() {
        String escaped = YamlScalar.doubleQuoted("key\"value");

        assertEquals("\"key\\\"value\"", escaped);
    }

    @Test
    void escapesBackslashesAndNewlines() {
        String escaped = YamlScalar.doubleQuoted("back\\slash\\nline");

        assertEquals("\"back\\\\slash\\\\nline\"", escaped);
    }

    @Test
    void preservesSupplementaryCharacters() {
        String escaped = YamlScalar.doubleQuoted("😀");

        assertEquals("\"😀\"", escaped);
    }

    @Test
    void escapesLoneHighSurrogatesInUnicodeForm() {
        String escaped = YamlScalar.doubleQuoted(String.valueOf((char) 0xD800));

        assertEquals("\"\\ud800\"", escaped);
    }
}
