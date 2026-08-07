package dev.eugene.publicationexporter.reference;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceMapTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void accessorsReturnConstructedValues() {
        ReferenceMap map = referenceMap();

        assertEquals(1, map.schemaVersion());
        assertEquals(IDENTITY, map.identity());
        assertEquals("ru-hash", map.ruHash());
        assertEquals("en-hash", map.enHash());
        assertEquals("ru-title-hash", map.ruTitleHash());
        assertEquals("en-title-hash", map.enTitleHash());
        assertEquals("ru-description-hash", map.ruDescriptionHash());
        assertEquals("en-description-hash", map.enDescriptionHash());
    }

    @Test
    void occurrencesIsAlwaysEmpty() {
        ReferenceMap map = referenceMap();

        assertTrue(map.occurrences().isEmpty());
    }

    @Test
    void equalMapsBuiltSeparatelyAreEqual() {
        assertEquals(
                referenceMap(),
                referenceMap());
        assertEquals(referenceMap().hashCode(), referenceMap().hashCode());
    }

    @Test
    void metadataHashesParticipateInValueSemantics() {
        ReferenceMap changedEnglishDescription = ReferenceMap.empty(
                IDENTITY, "ru-hash", "en-hash",
                "ru-title-hash", "en-title-hash",
                "ru-description-hash", "changed-en-description-hash");

        assertNotEquals(referenceMap(), changedEnglishDescription);
        assertNotEquals(referenceMap().hashCode(), changedEnglishDescription.hashCode());
        assertTrue(referenceMap().toString().contains("ruTitleHash=ru-title-hash"));
        assertTrue(referenceMap().toString().contains("enDescriptionHash=en-description-hash"));
    }

    @Test
    void sameContentAsComparesContentHashesWithoutConsideringIdentity() {
        ReferenceMap sameContent = ReferenceMap.empty(
                PublicationIdentity.of("other", "essay", "other-essay"),
                "ru-hash", "en-hash",
                "ru-title-hash", "en-title-hash",
                "ru-description-hash", "en-description-hash");
        ReferenceMap changedContent = ReferenceMap.empty(
                IDENTITY, "ru-hash", "en-hash",
                "ru-title-hash", "en-title-hash",
                "ru-description-hash", "changed-en-description-hash");

        assertTrue(referenceMap().sameContentAs(sameContent));
        assertFalse(referenceMap().sameContentAs(changedContent));
    }

    @Test
    void sameContentAsRejectsNull() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> referenceMap().sameContentAs(null));

        assertEquals("other", exception.getMessage());
    }

    @Test
    void ruHashIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReferenceMap.empty(
                        IDENTITY, null, "en-hash",
                        "ru-title-hash", "en-title-hash",
                        "ru-description-hash", "en-description-hash"));
        assertEquals("ruHash", exception.getMessage());
    }

    @Test
    void metadataHashIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReferenceMap.empty(
                        IDENTITY, "ru-hash", "en-hash",
                        "ru-title-hash", "en-title-hash",
                        "ru-description-hash", null));

        assertEquals("enDescriptionHash", exception.getMessage());
    }

    private static ReferenceMap referenceMap() {
        return ReferenceMap.empty(
                IDENTITY, "ru-hash", "en-hash",
                "ru-title-hash", "en-title-hash",
                "ru-description-hash", "en-description-hash");
    }
}
