package dev.eugene.publicationexporter.legacy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

public final class LegacyMigrationDecisionCodec {

    private static final Set<String> DECISION_FIELDS = Set.of("schemaVersion", "inventorySha256");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public String draftFor(LegacyWorkspaceInventory inventory) {
        Objects.requireNonNull(inventory, "inventory");
        ObjectNode draft = MAPPER.createObjectNode();
        draft.put("schemaVersion", 1);
        draft.put("draftOnly", true);
        draft.put("status", "human-resolution-required");
        draft.set("inventory", MAPPER.valueToTree(inventory));
        draft.set("decisionTemplate", MAPPER.valueToTree(
                new MigrationDecisionSet(1, inventory.inventorySha256())));
        return write(draft);
    }

    public MigrationDecisionSet decisionsFrom(String json) {
        JsonNode root = executableDecisionRoot(json);
        return new MigrationDecisionSet(requiredSchemaVersion(root), requiredFingerprint(root));
    }

    private JsonNode executableDecisionRoot(String json) {
        if (json == null) {
            throw new LegacyMigrationDecisionException("Decision JSON must not be null.");
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                throw new LegacyMigrationDecisionException("Decision JSON must be an object.");
            }
            rejectUnknownFields(root);
            rejectDraftMarker(root);
            return root;
        } catch (LegacyMigrationDecisionException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new LegacyMigrationDecisionException("Decision JSON is malformed.", exception);
        }
    }

    private void rejectUnknownFields(JsonNode root) {
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!DECISION_FIELDS.contains(field)) {
                throw new LegacyMigrationDecisionException("Unknown decision field: " + field);
            }
        }
    }

    private void rejectDraftMarker(JsonNode root) {
        if (root.has("draftOnly") || root.has("status") || root.has("decisionTemplate") || root.has("inventory")) {
            throw new LegacyMigrationDecisionException("A generated draft cannot be used as a decision file.");
        }
    }

    private int requiredSchemaVersion(JsonNode root) {
        JsonNode schemaVersion = root.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isIntegralNumber() || !schemaVersion.canConvertToInt()) {
            throw new LegacyMigrationDecisionException("Decision schemaVersion must be an integer.");
        }
        return schemaVersion.intValue();
    }

    private String requiredFingerprint(JsonNode root) {
        JsonNode fingerprint = root.get("inventorySha256");
        if (fingerprint == null || !fingerprint.isTextual()) {
            throw new LegacyMigrationDecisionException("Decision inventorySha256 must be a string.");
        }
        return fingerprint.textValue();
    }

    private String write(ObjectNode draft) {
        try {
            return MAPPER.writeValueAsString(draft);
        } catch (JsonProcessingException exception) {
            throw new LegacyMigrationDecisionException("Unable to write decision draft.", exception);
        }
    }
}
