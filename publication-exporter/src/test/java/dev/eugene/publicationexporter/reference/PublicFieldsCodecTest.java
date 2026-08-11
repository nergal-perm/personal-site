package dev.eugene.publicationexporter.reference;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicFieldsCodecTest {

    @Test
    void writeThenReadRoundTripsToAnEqualFieldList() {
        List<PublicField> original = List.of(
                PublicField.of("title", "Title"),
                PublicField.of("description", "Description."));

        List<PublicField> roundTripped = PublicFieldsCodec.read(PublicFieldsCodec.write(original));

        assertEquals(original, roundTripped);
    }

    @Test
    void emptyListRoundTrips() {
        assertEquals(List.of(), PublicFieldsCodec.read(PublicFieldsCodec.write(List.of())));
    }

    @Test
    void orderingIsPreserved() {
        List<PublicField> fields = List.of(
                PublicField.of("first", "1"),
                PublicField.of("second", "2"),
                PublicField.of("third", "3"));

        assertEquals(fields, PublicFieldsCodec.read(PublicFieldsCodec.write(fields)));
    }
}
