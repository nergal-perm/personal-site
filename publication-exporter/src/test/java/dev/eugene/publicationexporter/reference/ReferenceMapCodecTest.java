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
        ReferenceMap map = ReferenceMap.empty(identity, "ru-hash", "en-hash");

        String json = ReferenceMapCodec.write(map);
        JsonNode parsed = new ObjectMapper().readTree(json);

        assertEquals(1, parsed.get("schemaVersion").asInt());
        assertEquals("blog", parsed.get("publicationIdentity").get("publicCollection").asText());
        assertEquals("my-essay", parsed.get("publicationIdentity").get("publicId").asText());
        assertEquals("ru-hash", parsed.get("ruHash").asText());
        assertEquals("en-hash", parsed.get("enHash").asText());
        assertTrue(parsed.get("occurrences").isArray());
        assertEquals(0, parsed.get("occurrences").size());
    }

    @Test
    void readReturnsTheIdentityAndHashesTheJsonCarries() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        String json = ReferenceMapCodec.write(ReferenceMap.empty(identity, "ru-hash", "en-hash"));

        ReferenceMap parsed = ReferenceMapCodec.read(json);

        assertEquals(identity, parsed.identity());
        assertEquals("ru-hash", parsed.ruHash());
        assertEquals("en-hash", parsed.enHash());
        assertTrue(parsed.occurrences().isEmpty());
    }

    @Test
    void writeThenReadRoundTripsToAnEqualMap() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ReferenceMap original = ReferenceMap.empty(identity, "ru-hash", "en-hash");

        ReferenceMap roundTripped = ReferenceMapCodec.read(ReferenceMapCodec.write(original));

        assertEquals(original, roundTripped);
    }
}
