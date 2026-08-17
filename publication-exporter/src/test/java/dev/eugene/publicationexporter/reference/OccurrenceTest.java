package dev.eugene.publicationexporter.reference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OccurrenceTest {

    @Test
    void accessorsReturnConstructedValues() {
        Occurrence occurrence = new Occurrence("occ-1", 0, "src-a", "дед Шведов", "Grandpa Shvedov");

        assertEquals("occ-1", occurrence.id());
        assertEquals(0, occurrence.order());
        assertEquals("src-a", occurrence.targetSourceId());
        assertEquals("дед Шведов", occurrence.ruLabel());
        assertEquals("Grandpa Shvedov", occurrence.enLabel());
    }

    @Test
    void idIsRejectedWhenNull() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> new Occurrence(null, 0, "src-a", "ru", "en"));
        assertEquals("id", exception.getMessage());
    }

    @Test
    void targetSourceIdIsRejectedWhenNull() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> new Occurrence("occ-1", 0, null, "ru", "en"));
        assertEquals("targetSourceId", exception.getMessage());
    }
}
