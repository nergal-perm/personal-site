package dev.eugene.publicationexporter.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMigrationDecisionCodecTest {

    @Test
    void draftIsDeterministicAndPermanentlyNonExecutable() throws Exception {
        LegacyWorkspaceInventory inventory = new LegacyWorkspaceInventory(
                List.of(), List.of(), List.of(), List.of(), "a".repeat(64));

        String draft = new LegacyMigrationDecisionCodec().draftFor(inventory);
        assertEquals(draft, new LegacyMigrationDecisionCodec().draftFor(inventory));
        JsonNode root = new ObjectMapper().readTree(draft);

        assertTrue(root.get("draftOnly").asBoolean());
        assertEquals("human-resolution-required", root.get("status").asText());
        assertEquals("{\"schemaVersion\":1,\"inventorySha256\":\"" + "a".repeat(64) + "\"}",
                root.get("decisionTemplate").toString());
        assertThrows(LegacyMigrationDecisionException.class,
                () -> new LegacyMigrationDecisionCodec().decisionsFrom(draft));
    }

    @Test
    void decisionReaderRejectsDuplicateUnknownMissingAndMalformedFields() {
        LegacyMigrationDecisionCodec codec = new LegacyMigrationDecisionCodec();
        String fingerprint = "a".repeat(64);

        assertThrows(LegacyMigrationDecisionException.class,
                () -> codec.decisionsFrom("{\"schemaVersion\":1,\"schemaVersion\":1,\"inventorySha256\":\"a\"}"));
        assertThrows(LegacyMigrationDecisionException.class,
                () -> codec.decisionsFrom("{\"schemaVersion\":1,\"inventorySha256\":\"" + fingerprint
                        + "\",\"unknown\":true}"));
        assertThrows(LegacyMigrationDecisionException.class,
                () -> codec.decisionsFrom("{\"schemaVersion\":1}"));
        assertThrows(LegacyMigrationDecisionException.class,
                () -> codec.decisionsFrom("{\"schemaVersion\":\"1\",\"inventorySha256\":\"" + fingerprint + "\"}"));
        assertThrows(LegacyMigrationDecisionException.class,
                () -> codec.decisionsFrom("{\"schemaVersion\":1,\"inventorySha256\":\"not-a-fingerprint\"}"));
        assertThrows(LegacyMigrationDecisionException.class,
                () -> codec.decisionsFrom("{\"schemaVersion\":1,\"inventorySha256\":\"" + fingerprint
                        + "\",\"draftOnly\":true}"));
        assertThrows(LegacyMigrationDecisionException.class,
                () -> codec.decisionsFrom("not-json"));
        assertThrows(LegacyMigrationDecisionException.class,
                () -> codec.decisionsFrom("{\"schemaVersion\":1,\"inventorySha256\":\"" + fingerprint
                        + "\"}{\"extra\":true}"));
        assertThrows(LegacyMigrationDecisionException.class,
                () -> codec.decisionsFrom("{\"schemaVersion\":1,\"inventorySha256\":\"" + fingerprint
                        + "\"}trailing"));
        assertThrows(LegacyMigrationDecisionException.class,
                () -> codec.decisionsFrom("{\"schemaVersion\":4294967297,\"inventorySha256\":\""
                        + fingerprint + "\"}"));
    }

    @Test
    void freshDecisionRoundTripsToAnImmutableValue() {
        String fingerprint = "b".repeat(64);

        MigrationDecisionSet decisions = new LegacyMigrationDecisionCodec()
                .decisionsFrom("{\"schemaVersion\":1,\"inventorySha256\":\"" + fingerprint + "\"}");

        assertEquals(new MigrationDecisionSet(1, fingerprint), decisions);
    }
}
