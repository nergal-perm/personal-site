package dev.eugene.publicationexporter.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReleaseProvenanceTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void accessorsReturnConstructedValuesWithZeroActivationCounts() {
        ReleaseProvenance provenance = ReleaseProvenance.of(IDENTITY, "approved-ru", "approved-en", "output-ru", "output-en");

        assertEquals(1, provenance.contractEdition());
        assertEquals(IDENTITY, provenance.identity());
        assertEquals("approved-ru", provenance.approvedRuHash());
        assertEquals("approved-en", provenance.approvedEnHash());
        assertEquals("output-ru", provenance.outputRuHash());
        assertEquals("output-en", provenance.outputEnHash());
        assertEquals(0, provenance.activationCount());
        assertEquals(0, provenance.deactivationCount());
    }

    @Test
    void equalProvenanceBuiltSeparatelyAreEqual() {
        assertEquals(
                ReleaseProvenance.of(IDENTITY, "ru", "en", "ru", "en"),
                ReleaseProvenance.of(IDENTITY, "ru", "en", "ru", "en"));
    }

    @Test
    void explicitActivationCountsAreExposedAndParticipateInEquality() {
        ReleaseProvenance provenance = ReleaseProvenance.of(
                IDENTITY, "ru", "en", "ru", "en", 2, 1);

        assertEquals(2, provenance.activationCount());
        assertEquals(1, provenance.deactivationCount());
        assertNotEquals(provenance, ReleaseProvenance.of(
                IDENTITY, "ru", "en", "ru", "en", 1, 2));
    }

    @Test
    void identityIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReleaseProvenance.of(null, "ru", "en", "ru", "en"));
        assertEquals("identity", exception.getMessage());
    }

    @Test
    void serializesEveryFieldAsJson() throws Exception {
        ReleaseProvenance provenance = ReleaseProvenance.of(IDENTITY, "approved-ru", "approved-en", "output-ru", "output-en");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(provenance));

        assertEquals(1, json.get("contractEdition").asInt());
        assertEquals("my-essay", json.get("publicationIdentity").get("publicId").asText());
        assertEquals("approved-ru", json.get("approvedRuHash").asText());
        assertEquals("approved-en", json.get("approvedEnHash").asText());
        assertEquals("output-ru", json.get("outputRuHash").asText());
        assertEquals("output-en", json.get("outputEnHash").asText());
        assertEquals(0, json.get("activationCount").asInt());
        assertEquals(0, json.get("deactivationCount").asInt());
    }

    @Test
    void serializesNonzeroActivationAndDeactivationCountsAsJson() throws Exception {
        ReleaseProvenance provenance = ReleaseProvenance.of(
                IDENTITY, "approved-ru", "approved-en", "output-ru", "output-en", 2, 1);

        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(provenance));

        assertEquals(2, json.get("activationCount").asInt());
        assertEquals(1, json.get("deactivationCount").asInt());
    }
}
