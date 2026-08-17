package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReferenceMapCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReferenceMapCodec() {
    }

    public static String write(ReferenceMap referenceMap) {
        try {
            return MAPPER.writeValueAsString(referenceMap);
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
        return ReferenceMap.of(
                identity,
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
            JsonNode orderNode = node.get("order");
            if (orderNode == null || !orderNode.isIntegralNumber() || !orderNode.canConvertToInt()) {
                throw new ReferenceMapCodecException("Occurrence \"" + id + "\" has an invalid order.");
            }
            int order = orderNode.asInt();
            if (order != expectedOrder) {
                throw new ReferenceMapCodecException(
                        "Occurrence \"" + id + "\" has order " + order + " but position " + expectedOrder + ".");
            }
            occurrences.add(new Occurrence(
                    id, order,
                    requiredText(node, "targetSourceId"),
                    requiredText(node, "ruLabel"),
                    requiredText(node, "enLabel")));
            expectedOrder++;
        }
        return occurrences;
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
