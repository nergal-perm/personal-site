package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateSnapshotTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final ReferenceMap REFERENCE_MAP = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

    @Test
    void accessorsReturnConstructedValues() {
        CandidateSnapshot snapshot = CandidateSnapshot.of("RU body", "EN body", REFERENCE_MAP);

        assertEquals("RU body", snapshot.ruBody());
        assertEquals("EN body", snapshot.enBody());
        assertEquals(REFERENCE_MAP, snapshot.referenceMap());
    }

    @Test
    void equalSnapshotsBuiltSeparatelyAreEqual() {
        assertEquals(
                CandidateSnapshot.of("RU", "EN", REFERENCE_MAP),
                CandidateSnapshot.of("RU", "EN", REFERENCE_MAP));
    }

    @Test
    void ruBodyIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> CandidateSnapshot.of(null, "EN", REFERENCE_MAP));
        assertEquals("ruBody", exception.getMessage());
    }
}
