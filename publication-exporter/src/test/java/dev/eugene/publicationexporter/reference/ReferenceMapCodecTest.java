package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void writeProducesOccurrencesInOrder() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        Occurrence first = new Occurrence("occ-1", 0, "src-a", "ru-label-a", "en-label-a");
        Occurrence second = new Occurrence("occ-2", 1, "src-b", "ru-label-b", "en-label-b");
        ReferenceMap map = ReferenceMap.of(
                identity, "ru-hash", "en-hash", "ru-fields-hash", "en-fields-hash", "structured-data-hash",
                List.of(first, second));

        String json = ReferenceMapCodec.write(map);
        JsonNode occurrences = new ObjectMapper().readTree(json).get("occurrences");

        assertEquals(2, occurrences.size());
        assertEquals("occ-1", occurrences.get(0).get("id").asText());
        assertEquals(0, occurrences.get(0).get("order").asInt());
        assertEquals("src-a", occurrences.get(0).get("targetSourceId").asText());
        assertEquals("ru-label-a", occurrences.get(0).get("ruLabel").asText());
        assertEquals("en-label-a", occurrences.get(0).get("enLabel").asText());
        assertEquals("occ-2", occurrences.get(1).get("id").asText());
        assertEquals(1, occurrences.get(1).get("order").asInt());
        assertEquals("src-b", occurrences.get(1).get("targetSourceId").asText());
        assertEquals("ru-label-b", occurrences.get(1).get("ruLabel").asText());
        assertEquals("en-label-b", occurrences.get(1).get("enLabel").asText());
    }

    @Test
    void writeThenReadRoundTripsOccurrences() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        Occurrence occurrence = new Occurrence("occ-1", 0, "src-a", "ru-label", "en-label");
        ReferenceMap original = ReferenceMap.of(
                identity, "ru-hash", "en-hash", "ru-fields-hash", "en-fields-hash", "structured-data-hash",
                List.of(occurrence));

        ReferenceMap roundTripped = ReferenceMapCodec.read(ReferenceMapCodec.write(original));

        assertEquals(original, roundTripped);
    }

    @Test
    void readRejectsDuplicateOccurrenceIds() {
        String json = referenceMapJsonWithOccurrences(
                "{\"id\":\"occ-1\",\"order\":0,\"targetSourceId\":\"src-a\",\"ruLabel\":\"ru\",\"enLabel\":\"en\"},"
                + "{\"id\":\"occ-1\",\"order\":1,\"targetSourceId\":\"src-b\",\"ruLabel\":\"ru2\",\"enLabel\":\"en2\"}");

        assertThrows(ReferenceMapCodecException.class, () -> ReferenceMapCodec.read(json));
    }

    @Test
    void readRejectsOrderNotMatchingArrayPosition() {
        String json = referenceMapJsonWithOccurrences(
                "{\"id\":\"occ-1\",\"order\":1,\"targetSourceId\":\"src-a\",\"ruLabel\":\"ru\",\"enLabel\":\"en\"}");

        assertThrows(ReferenceMapCodecException.class, () -> ReferenceMapCodec.read(json));
    }

    private static String referenceMapJsonWithOccurrences(String occurrencesJson) {
        return "{\"schemaVersion\":1,"
                + "\"publicationIdentity\":{\"publicCollection\":\"blog\",\"publicContentType\":\"essay\",\"publicId\":\"my-essay\"},"
                + "\"ruHash\":\"ru-hash\",\"enHash\":\"en-hash\","
                + "\"ruFieldsHash\":\"ru-fields-hash\",\"enFieldsHash\":\"en-fields-hash\","
                + "\"structuredDataHash\":\"structured-data-hash\","
                + "\"occurrences\":[" + occurrencesJson + "]}";
    }

    @Test
    void readReturnsTheIdentityAndHashesTheJsonCarries() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        String json = ReferenceMapCodec.write(referenceMap(identity));

        assertFalse(new ObjectMapper().readTree(json).has("sourceId"));

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
    void writeThenReadRoundTripsSourceIdThroughNewOverload() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ReferenceMap original = ReferenceMap.of(
                identity, "vault-source-id-a", "ru-hash", "en-hash",
                "ru-fields-hash", "en-fields-hash", "structured-data-hash", List.of());

        ReferenceMap roundTripped = ReferenceMapCodec.read(ReferenceMapCodec.write(original));

        assertEquals(Optional.of("vault-source-id-a"), roundTripped.sourceId());
        assertEquals(original, roundTripped);
    }

    @Test
    void writeThenReadRoundTripsSourceBodyHashThroughNewOverload() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ReferenceMap original = ReferenceMap.of(
                identity, "vault-source-id-a", "ru-hash", "en-hash",
                "ru-fields-hash", "en-fields-hash", "structured-data-hash", List.of(),
                "source-body-hash");

        String json = ReferenceMapCodec.write(original);
        ReferenceMap roundTripped = ReferenceMapCodec.read(json);

        assertEquals("source-body-hash", new ObjectMapper().readTree(json).get("sourceBodyHash").asText());
        assertEquals("source-body-hash", roundTripped.sourceBodyHash());
        assertEquals(original, roundTripped);
    }

    @Test
    void readWithoutSourceIdInJsonDefaultsToEmpty() {
        String json = "{\"schemaVersion\":1,"
                + "\"publicationIdentity\":{\"publicCollection\":\"blog\",\"publicContentType\":\"essay\",\"publicId\":\"my-essay\"},"
                + "\"ruHash\":\"ru-hash\",\"enHash\":\"en-hash\","
                + "\"ruFieldsHash\":\"ru-fields-hash\",\"enFieldsHash\":\"en-fields-hash\","
                + "\"structuredDataHash\":\"structured-data-hash\","
                + "\"occurrences\":[]}";

        ReferenceMap parsed = ReferenceMapCodec.read(json);

        assertEquals(Optional.empty(), parsed.sourceId());
        assertEquals("", parsed.sourceBodyHash());
    }

    @Test
    void readWithoutSourceBodyHashInJsonDefaultsToEmptySentinel() {
        String json = "{\"schemaVersion\":1,"
                + "\"publicationIdentity\":{\"publicCollection\":\"blog\",\"publicContentType\":\"essay\",\"publicId\":\"my-essay\"},"
                + "\"sourceId\":\"vault-source-id-a\","
                + "\"ruHash\":\"ru-hash\",\"enHash\":\"en-hash\","
                + "\"ruFieldsHash\":\"ru-fields-hash\",\"enFieldsHash\":\"en-fields-hash\","
                + "\"structuredDataHash\":\"structured-data-hash\","
                + "\"occurrences\":[]}";

        ReferenceMap parsed = ReferenceMapCodec.read(json);

        assertEquals(Optional.of("vault-source-id-a"), parsed.sourceId());
        assertEquals("", parsed.sourceBodyHash());
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
