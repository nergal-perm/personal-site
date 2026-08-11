package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateSnapshotTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final List<PublicField> RU_FIELDS = List.of(
            PublicField.of("title", "RU title"),
            PublicField.of("description", "RU description"));
    private static final List<PublicField> EN_FIELDS = List.of(
            PublicField.of("title", "EN title"),
            PublicField.of("description", "EN description"));
    private static final ReferenceMap REFERENCE_MAP = ReferenceMap.empty(
            IDENTITY, "ru-hash", "en-hash", "ru-fields-hash", "en-fields-hash", "structured-data-hash");

    @Test
    void accessorsReturnConstructedValues() {
        CandidateSnapshot snapshot = CandidateSnapshot.of("RU body", "EN body",
                RU_FIELDS, EN_FIELDS, "structured data", REFERENCE_MAP);

        assertEquals("RU body", snapshot.ruBody());
        assertEquals("EN body", snapshot.enBody());
        assertEquals(RU_FIELDS, snapshot.ruFields());
        assertEquals(EN_FIELDS, snapshot.enFields());
        assertEquals("structured data", snapshot.structuredData());
        assertEquals(REFERENCE_MAP, snapshot.referenceMap());
    }

    @Test
    void equalSnapshotsBuiltSeparatelyAreEqual() {
        assertEquals(
                CandidateSnapshot.of("RU", "EN", RU_FIELDS, EN_FIELDS,
                        "structured data", REFERENCE_MAP),
                CandidateSnapshot.of("RU", "EN", RU_FIELDS, EN_FIELDS,
                        "structured data", REFERENCE_MAP));
    }

    @Test
    void ruBodyIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> CandidateSnapshot.of(null, "EN", RU_FIELDS, EN_FIELDS,
                        "structured data", REFERENCE_MAP));
        assertEquals("ruBody", exception.getMessage());
    }
}
