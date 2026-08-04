package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeResponseJsonTest {

    @Test
    void blockedResponseSerializesToSchemaV2Shape() throws Exception {
        BridgeResponse response = BridgeResponse.blocked(
                "inspect-publication",
                Diagnostic.blocking("note", "Note was not found in the vault."));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(2, parsed.get("schemaVersion").asInt());
        assertEquals("inspect-publication", parsed.get("command").asText());
        assertEquals(false, parsed.get("ok").asBoolean());
        assertEquals("metadata_blocked", parsed.get("status").asText());
        assertTrue(parsed.get("diagnostics").isArray());
        assertEquals(1, parsed.get("diagnostics").size());
        JsonNode diagnostic = parsed.get("diagnostics").get(0);
        assertEquals("note", diagnostic.get("field").asText());
        assertEquals("Note was not found in the vault.", diagnostic.get("message").asText());
        assertEquals(true, diagnostic.get("blocking").asBoolean());
        assertTrue(parsed.get("workspaceHealth").isArray());
        assertEquals(0, parsed.get("workspaceHealth").size());
    }

    @Test
    void blockedResponsesBuiltSeparatelyWithSameValuesAreEqual() {
        assertEquals(
                BridgeResponse.blocked("inspect-publication", Diagnostic.blocking("note", "msg")),
                BridgeResponse.blocked("inspect-publication", Diagnostic.blocking("note", "msg")));
    }

    @Test
    void diagnosticFieldIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> Diagnostic.blocking(null, "msg"));
        assertEquals("field", exception.getMessage());
    }

    @Test
    void bridgeResponseCommandIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> BridgeResponse.blocked(null, Diagnostic.blocking("note", "msg")));
        assertEquals("command", exception.getMessage());
    }

    @Test
    void essayInspectedResponseSerializesToSchemaV2Shape() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        BridgeResponse response = BridgeResponse.essayInspected(
                "inspect-publication", "not_prepared", identity,
                "absent", "absent", "absent", "absent");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(2, parsed.get("schemaVersion").asInt());
        assertEquals(true, parsed.get("ok").asBoolean());
        assertEquals("not_prepared", parsed.get("status").asText());
        assertEquals("blog", parsed.get("identity").get("publicCollection").asText());
        assertEquals("absent", parsed.get("candidateState").asText());
        assertEquals("absent", parsed.get("approvedSnapshotState").asText());
        assertEquals("absent", parsed.get("semanticReferenceState").asText());
        assertEquals("absent", parsed.get("releaseState").asText());
        assertTrue(parsed.get("diagnostics").isArray());
        assertEquals(0, parsed.get("diagnostics").size());
    }

    @Test
    void blockedResponseOmitsIdentityAndStateFieldsFromJson() throws Exception {
        BridgeResponse response = BridgeResponse.blocked(
                "inspect-publication", Diagnostic.blocking("id", "Note has no source ID."));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertFalse(parsed.has("identity"));
        assertFalse(parsed.has("candidateState"));
    }

    @Test
    void blockedResponseAcceptsMultipleDiagnostics() {
        BridgeResponse response = BridgeResponse.blocked("inspect-publication", List.of(
                Diagnostic.blocking("publicCollection", "must be \"blog\""),
                Diagnostic.blocking("publicContentType", "requires a valid publicCollection")));

        assertEquals(2, response.diagnostics().size());
    }

    @Test
    void preparedResponseSerializesToLeanShapeWithNoStateFields() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        BridgeResponse response = BridgeResponse.prepared("prepare", identity);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(2, parsed.get("schemaVersion").asInt());
        assertEquals(true, parsed.get("ok").asBoolean());
        assertEquals("ready_for_review", parsed.get("status").asText());
        assertEquals("my-essay", parsed.get("identity").get("publicId").asText());
        assertEquals(0, parsed.get("diagnostics").size());
        assertFalse(parsed.has("candidateState"));
        assertFalse(parsed.has("approvedSnapshotState"));
        assertFalse(parsed.has("semanticReferenceState"));
        assertFalse(parsed.has("releaseState"));
    }

    @Test
    void translationFailedResponseCarriesTheFailedStatusAndDiagnostics() throws Exception {
        BridgeResponse response = BridgeResponse.translationFailed(
                "prepare", Diagnostic.blocking("candidate", "Translation worker did not return a usable result."));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(false, parsed.get("ok").asBoolean());
        assertEquals("translation_failed", parsed.get("status").asText());
        assertEquals(1, parsed.get("diagnostics").size());
        assertFalse(parsed.has("identity"));
    }
}
