package dev.eugene.publicationexporter.reference;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
        assertEquals("ru-fields-hash", map.ruFieldsHash());
        assertEquals("en-fields-hash", map.enFieldsHash());
        assertEquals("structured-data-hash", map.structuredDataHash());
    }

    @Test
    void occurrencesIsEmptyForTheEmptyFactory() {
        ReferenceMap map = referenceMap();

        assertTrue(map.occurrences().isEmpty());
    }

    @Test
    void occurrencesReturnsConstructedListInOrder() {
        Occurrence first = new Occurrence("occ-1", 0, "src-a", "ru-a", "en-a");
        Occurrence second = new Occurrence("occ-2", 1, "src-b", "ru-b", "en-b");
        ReferenceMap map = ReferenceMap.of(
                IDENTITY, "ru-hash", "en-hash",
                "ru-fields-hash", "en-fields-hash", "structured-data-hash",
                List.of(first, second));

        assertEquals(List.of(first, second), map.occurrences());
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
        ReferenceMap changedStructuredData = ReferenceMap.empty(
                IDENTITY, "ru-hash", "en-hash",
                "ru-fields-hash", "en-fields-hash", "changed-structured-data-hash");

        assertNotEquals(referenceMap(), changedStructuredData);
        assertNotEquals(referenceMap().hashCode(), changedStructuredData.hashCode());
        assertTrue(referenceMap().toString().contains("ruFieldsHash=ru-fields-hash"));
        assertTrue(referenceMap().toString().contains("structuredDataHash=structured-data-hash"));
    }

    @Test
    void sameContentAsComparesContentHashesWithoutConsideringIdentity() {
        ReferenceMap sameContent = ReferenceMap.empty(
                PublicationIdentity.of("other", "essay", "other-essay"),
                "ru-hash", "en-hash",
                "ru-fields-hash", "en-fields-hash", "structured-data-hash");
        ReferenceMap changedContent = ReferenceMap.empty(
                IDENTITY, "ru-hash", "en-hash",
                "ru-fields-hash", "en-fields-hash", "changed-structured-data-hash");

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
    void ofWithSourceIdRecordsIt() {
        ReferenceMap referenceMap = ReferenceMap.of(
                IDENTITY, "vault-source-id-a", "ru-hash", "en-hash",
                "ru-fields-hash", "en-fields-hash", "structured-data-hash", List.of());

        assertEquals(Optional.of("vault-source-id-a"), referenceMap.sourceId());
    }

    @Test
    void existingSevenArgFactoriesDefaultSourceIdToEmpty() {
        ReferenceMap viaOf = ReferenceMap.of(
                IDENTITY, "ru-hash", "en-hash", "ru-fields-hash", "en-fields-hash", "structured-data-hash", List.of());
        ReferenceMap viaEmpty = ReferenceMap.empty(
                IDENTITY, "ru-hash", "en-hash", "ru-fields-hash", "en-fields-hash", "structured-data-hash");

        assertEquals(Optional.empty(), viaOf.sourceId());
        assertEquals(Optional.empty(), viaEmpty.sourceId());
    }

    @Test
    void sourceIdParticipatesInEqualsAndHashCode() {
        ReferenceMap withSourceId = ReferenceMap.of(
                IDENTITY, "vault-source-id-a", "ru-hash", "en-hash",
                "ru-fields-hash", "en-fields-hash", "structured-data-hash", List.of());
        ReferenceMap withoutSourceId = ReferenceMap.of(
                IDENTITY, "ru-hash", "en-hash", "ru-fields-hash", "en-fields-hash", "structured-data-hash", List.of());

        assertNotEquals(withSourceId, withoutSourceId);
    }

    @Test
    void ruHashIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReferenceMap.empty(
                        IDENTITY, null, "en-hash",
                        "ru-fields-hash", "en-fields-hash", "structured-data-hash"));
        assertEquals("ruHash", exception.getMessage());
    }

    @Test
    void metadataHashIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReferenceMap.empty(
                        IDENTITY, "ru-hash", "en-hash",
                        "ru-fields-hash", "en-fields-hash", null));

        assertEquals("structuredDataHash", exception.getMessage());
    }

    private static ReferenceMap referenceMap() {
        return ReferenceMap.empty(
                IDENTITY, "ru-hash", "en-hash",
                "ru-fields-hash", "en-fields-hash", "structured-data-hash");
    }
}
