package dev.eugene.publicationexporter.reference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

public final class PublicFieldsCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PublicFieldsCodec() {
    }

    public static String write(List<PublicField> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(new IOException(error));
        }
    }

    public static List<PublicField> read(String json) {
        try {
            List<Map<String, String>> raw = MAPPER.readValue(
                    json, new TypeReference<List<Map<String, String>>>() { });
            return raw.stream()
                    .map(entry -> PublicField.of(entry.get("key"), entry.get("value")))
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
