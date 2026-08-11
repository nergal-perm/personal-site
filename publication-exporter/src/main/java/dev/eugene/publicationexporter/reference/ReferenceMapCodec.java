package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.io.IOException;
import java.io.UncheckedIOException;

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
        return ReferenceMap.empty(
                identity,
                root.get("ruHash").asText(),
                root.get("enHash").asText(),
                root.get("ruFieldsHash").asText(),
                root.get("enFieldsHash").asText(),
                root.get("structuredDataHash").asText());
    }

    private static PublicationIdentity identityFrom(JsonNode identityNode) {
        return PublicationIdentity.of(
                identityNode.get("publicCollection").asText(),
                identityNode.get("publicContentType").asText(),
                identityNode.get("publicId").asText());
    }
}
