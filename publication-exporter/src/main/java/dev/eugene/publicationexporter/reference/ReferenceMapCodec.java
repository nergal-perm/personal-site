package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ReferenceMapCodec {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private ReferenceMapCodec() {
    }

    public static String write(ReferenceMap referenceMap) {
        try {
            var node = MAPPER.createObjectNode();
            node.put("schemaVersion", referenceMap.schemaVersion());
            node.set("publicationIdentity", MAPPER.valueToTree(referenceMap.identity()));
            node.put("ruHash", referenceMap.ruHash());
            node.put("enHash", referenceMap.enHash());
            node.put("ruFieldsHash", referenceMap.ruFieldsHash());
            node.put("enFieldsHash", referenceMap.enFieldsHash());
            node.put("structuredDataHash", referenceMap.structuredDataHash());
            referenceMap.sourceId().ifPresent(sourceId -> node.put("sourceId", sourceId));
            node.set("occurrences", MAPPER.valueToTree(referenceMap.occurrences()));
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(new IOException(error));
        }
    }

    public static ReferenceMap read(String json) {
        try {
            return referenceMapFrom(MAPPER.readTree(json));
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(new IOException(error));
        }
    }

    private static ReferenceMap referenceMapFrom(JsonNode root) {
        PublicationIdentity identity = identityFrom(root.get("publicationIdentity"));
        Optional<String> sourceId = Optional.ofNullable(root.get("sourceId"))
                .filter(node -> !node.isNull())
                .map(JsonNode::asText);
        if (sourceId.isEmpty()) {
            return ReferenceMap.of(
                    identity,
                    root.get("ruHash").asText(),
                    root.get("enHash").asText(),
                    root.get("ruFieldsHash").asText(),
                    root.get("enFieldsHash").asText(),
                    root.get("structuredDataHash").asText(),
                    occurrencesFrom(root.get("occurrences")));
        }
        return ReferenceMap.of(
                identity,
                sourceId.orElseThrow(),
                root.get("ruHash").asText(),
                root.get("enHash").asText(),
                root.get("ruFieldsHash").asText(),
                root.get("enFieldsHash").asText(),
                root.get("structuredDataHash").asText(),
                occurrencesFrom(root.get("occurrences")));
    }

    private static List<Occurrence> occurrencesFrom(JsonNode occurrencesNode) {
        if (occurrencesNode == null || !occurrencesNode.isArray()) {
            throw new ReferenceMapCodecException("Occurrences must be an array.");
        }
        List<Occurrence> occurrences = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int expectedOrder = 0;
        for (JsonNode node : occurrencesNode) {
            String id = requiredText(node, "id");
            if (!seenIds.add(id)) {
                throw new ReferenceMapCodecException("Duplicate occurrence id \"" + id + "\".");
            }
            occurrences.add(occurrenceFrom(node, id, expectedOrder));
            expectedOrder++;
        }
        return occurrences;
    }

    private static Occurrence occurrenceFrom(JsonNode node, String id, int expectedOrder) {
        JsonNode orderNode = node.get("order");
        if (orderNode == null || !orderNode.isIntegralNumber() || !orderNode.canConvertToInt()) {
            throw new ReferenceMapCodecException("Occurrence \"" + id + "\" has an invalid order.");
        }
        int order = orderNode.asInt();
        if (order != expectedOrder) {
            throw new ReferenceMapCodecException(
                    "Occurrence \"" + id + "\" has order " + order + " but position " + expectedOrder + ".");
        }
        return new Occurrence(
                id, order,
                requiredText(node, "targetSourceId"),
                requiredText(node, "ruLabel"),
                requiredText(node, "enLabel"));
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ReferenceMapCodecException("Occurrence field \"" + field + "\" must be non-blank text.");
        }
        return value.asText();
    }

    private static PublicationIdentity identityFrom(JsonNode identityNode) {
        return PublicationIdentity.of(
                identityNode.get("publicCollection").asText(),
                identityNode.get("publicContentType").asText(),
                identityNode.get("publicId").asText());
    }
}
