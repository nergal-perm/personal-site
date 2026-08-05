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
            JsonNode root = MAPPER.readTree(json);
            JsonNode identityNode = root.get("publicationIdentity");
            PublicationIdentity identity = PublicationIdentity.of(
                    identityNode.get("publicCollection").asText(),
                    identityNode.get("publicContentType").asText(),
                    identityNode.get("publicId").asText());
            return ReferenceMap.empty(identity, root.get("ruHash").asText(), root.get("enHash").asText());
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(new IOException(error));
        }
    }
}
