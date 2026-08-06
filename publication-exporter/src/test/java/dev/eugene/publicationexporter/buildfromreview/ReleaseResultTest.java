package dev.eugene.publicationexporter.buildfromreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.release.ReleaseProvenance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseResultTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final ReleaseProvenance PROVENANCE =
            ReleaseProvenance.of(IDENTITY, "ru-hash", "en-hash", "ru-hash", "en-hash");

    @Test
    void releasedSerializesOkTrueWithIdentityAndProvenanceAndNoMessage() throws Exception {
        ReleaseResult result = ReleaseResult.released(IDENTITY, PROVENANCE);

        assertTrue(result.ok());
        assertEquals(IDENTITY, result.identity());
        assertEquals(PROVENANCE, result.provenance());
        assertNull(result.message());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(result));
        assertEquals(true, json.get("ok").asBoolean());
        assertEquals("my-essay", json.get("identity").get("publicId").asText());
        assertTrue(json.has("provenance"));
        assertTrue(json.get("message").isNull());
    }

    @Test
    void blockedSerializesOkFalseWithMessageAndNoIdentityOrProvenance() throws Exception {
        ReleaseResult result = ReleaseResult.blocked("No approved snapshot exists to release.");

        assertFalse(result.ok());
        assertNull(result.identity());
        assertNull(result.provenance());
        assertEquals("No approved snapshot exists to release.", result.message());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(result));
        assertEquals(false, json.get("ok").asBoolean());
        assertTrue(json.get("identity").isNull());
        assertTrue(json.get("provenance").isNull());
        assertEquals("No approved snapshot exists to release.", json.get("message").asText());
    }
}
