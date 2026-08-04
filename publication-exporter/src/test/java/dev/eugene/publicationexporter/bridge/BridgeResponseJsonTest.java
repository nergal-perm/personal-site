package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
