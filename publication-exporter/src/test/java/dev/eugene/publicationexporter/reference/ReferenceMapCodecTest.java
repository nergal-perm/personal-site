package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceMapCodecTest {

    @Test
    void writeProducesTheDeclaredSchemaVersionIdentityHashesAndEmptyOccurrences() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ReferenceMap map = referenceMap(identity);

        String json = ReferenceMapCodec.write(map);
        JsonNode parsed = new ObjectMapper().readTree(json);

        assertEquals(1, parsed.get("schemaVersion").asInt());
        assertEquals("blog", parsed.get("publicationIdentity").get("publicCollection").asText());
        assertEquals("my-essay", parsed.get("publicationIdentity").get("publicId").asText());
        assertEquals("ru-hash", parsed.get("ruHash").asText());
        assertEquals("en-hash", parsed.get("enHash").asText());
        assertEquals("ru-fields-hash", parsed.get("ruFieldsHash").asText());
        assertEquals("en-fields-hash", parsed.get("enFieldsHash").asText());
        assertEquals("structured-data-hash", parsed.get("structuredDataHash").asText());
        assertTrue(parsed.get("occurrences").isArray());
        assertEquals(0, parsed.get("occurrences").size());
    }

    @Test
    void readReturnsTheIdentityAndHashesTheJsonCarries() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        String json = ReferenceMapCodec.write(referenceMap(identity));

        ReferenceMap parsed = ReferenceMapCodec.read(json);

        assertEquals(identity, parsed.identity());
        assertEquals("ru-hash", parsed.ruHash());
        assertEquals("en-hash", parsed.enHash());
        assertEquals("ru-fields-hash", parsed.ruFieldsHash());
        assertEquals("en-fields-hash", parsed.enFieldsHash());
        assertEquals("structured-data-hash", parsed.structuredDataHash());
        assertTrue(parsed.occurrences().isEmpty());
    }

    @Test
    void writeThenReadRoundTripsToAnEqualMap() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ReferenceMap original = referenceMap(identity);

        ReferenceMap roundTripped = ReferenceMapCodec.read(ReferenceMapCodec.write(original));

        assertEquals(original, roundTripped);
    }

    private static ReferenceMap referenceMap(PublicationIdentity identity) {
        return ReferenceMap.empty(
                identity, "ru-hash", "en-hash",
                "ru-fields-hash", "en-fields-hash", "structured-data-hash");
    }
}
