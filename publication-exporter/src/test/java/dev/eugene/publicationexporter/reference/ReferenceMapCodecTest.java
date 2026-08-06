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
        assertEquals("ru-title-hash", parsed.get("ruTitleHash").asText());
        assertEquals("en-title-hash", parsed.get("enTitleHash").asText());
        assertEquals("ru-description-hash", parsed.get("ruDescriptionHash").asText());
        assertEquals("en-description-hash", parsed.get("enDescriptionHash").asText());
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
        assertEquals("ru-title-hash", parsed.ruTitleHash());
        assertEquals("en-title-hash", parsed.enTitleHash());
        assertEquals("ru-description-hash", parsed.ruDescriptionHash());
        assertEquals("en-description-hash", parsed.enDescriptionHash());
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
                "ru-title-hash", "en-title-hash",
                "ru-description-hash", "en-description-hash");
    }
}
