package dev.eugene.publicationexporter.reference;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceMapTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void accessorsReturnConstructedValues() {
        ReferenceMap map = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        assertEquals(1, map.schemaVersion());
        assertEquals(IDENTITY, map.identity());
        assertEquals("ru-hash", map.ruHash());
        assertEquals("en-hash", map.enHash());
    }

    @Test
    void occurrencesIsAlwaysEmpty() {
        ReferenceMap map = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        assertTrue(map.occurrences().isEmpty());
    }

    @Test
    void equalMapsBuiltSeparatelyAreEqual() {
        assertEquals(
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"),
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));
    }

    @Test
    void ruHashIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReferenceMap.empty(IDENTITY, null, "en-hash"));
        assertEquals("ruHash", exception.getMessage());
    }
}
