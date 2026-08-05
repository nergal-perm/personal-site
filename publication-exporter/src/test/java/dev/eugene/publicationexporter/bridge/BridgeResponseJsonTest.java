package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
                "absent", "absent", "absent", "absent", null);

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
    void essayInspectedResponseOmitsReviewPlanFromJsonWhenNull() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        BridgeResponse response = BridgeResponse.essayInspected(
                "inspect-publication", "not_prepared", identity,
                "absent", "absent", "absent", "absent", null);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertFalse(parsed.has("reviewPlan"));
    }

    @Test
    void essayInspectedResponseIncludesReviewPlanWhenPresent() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        CandidatePaths candidatePaths = CandidatePaths.of(
                Path.of("/review/blog/my-essay/candidate/ru.md"),
                Path.of("/review/blog/my-essay/candidate/en.md"));
        BridgeResponse response = BridgeResponse.essayInspected(
                "inspect-publication", "ready_for_review", identity,
                "ready", "absent", "absent", "absent", ReviewPlan.firstPublication(candidatePaths));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals("absent", parsed.get("reviewPlan").get("baselineState").asText());
        assertEquals(2, parsed.get("reviewPlan").get("targets").size());
        assertEquals("ru", parsed.get("reviewPlan").get("targets").get(0).get("language").asText());
        assertTrue(parsed.get("reviewPlan").get("targets").get(0).get("publishedPath").isNull());
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

    @Test
    void approvedResponseSerializesToLeanShapeWithReadyToPublishStatus() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        BridgeResponse response = BridgeResponse.approved("mark-reviewed", identity);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(2, parsed.get("schemaVersion").asInt());
        assertEquals(true, parsed.get("ok").asBoolean());
        assertEquals("ready_to_publish", parsed.get("status").asText());
        assertEquals("my-essay", parsed.get("identity").get("publicId").asText());
        assertEquals(0, parsed.get("diagnostics").size());
        assertFalse(parsed.has("candidateState"));
        assertFalse(parsed.has("reviewPlan"));
    }

    @Test
    void staleResponseCarriesTheStaleStatusAndDiagnostics() throws Exception {
        BridgeResponse response = BridgeResponse.stale(
                "mark-reviewed", Diagnostic.blocking("candidate", "Source note has changed since preparation."));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals(false, parsed.get("ok").asBoolean());
        assertEquals("stale", parsed.get("status").asText());
        assertEquals(1, parsed.get("diagnostics").size());
        assertFalse(parsed.has("identity"));
    }
}
