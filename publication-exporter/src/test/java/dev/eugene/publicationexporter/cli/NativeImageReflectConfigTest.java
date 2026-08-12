package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeImageReflectConfigTest {

    @Test
    void kindContractReflectionConfigIncludesOptionalAndBlockedFieldAccessors() throws IOException {
        JsonNode reflectConfig = new ObjectMapper().readTree(reflectConfigResource());
        JsonNode kindContract = findKindContractEntry(reflectConfig);
        Set<String> methodNames = methodNames(kindContract.get("methods"));

        assertTrue(methodNames.contains("optionalFields"));
        assertTrue(methodNames.contains("blockedFields"));
    }

    @Test
    void referenceMapReflectionConfigIncludesCurrentFieldHashAccessors() throws IOException {
        JsonNode reflectConfig = new ObjectMapper().readTree(reflectConfigResource());
        JsonNode referenceMap = findEntry(reflectConfig, "dev.eugene.publicationexporter.reference.ReferenceMap");
        Set<String> methodNames = methodNames(referenceMap.get("methods"));

        assertTrue(methodNames.contains("ruFieldsHash"));
        assertTrue(methodNames.contains("enFieldsHash"));
        assertTrue(methodNames.contains("structuredDataHash"));
    }

    private InputStream reflectConfigResource() {
        InputStream resource = getClass().getClassLoader()
                .getResourceAsStream("META-INF/native-image/reflect-config.json");
        if (resource == null) {
            throw new IllegalStateException("Missing META-INF/native-image/reflect-config.json");
        }
        return resource;
    }

    private JsonNode findKindContractEntry(JsonNode reflectConfig) {
        return findEntry(reflectConfig, "dev.eugene.publicationexporter.contract.KindContract");
    }

    private JsonNode findEntry(JsonNode reflectConfig, String typeName) {
        for (JsonNode entry : reflectConfig) {
            if (typeName.equals(entry.get("name").asText())) {
                return entry;
            }
        }
        throw new IllegalStateException(typeName + " entry missing from native reflect config");
    }

    private Set<String> methodNames(JsonNode methods) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (JsonNode method : methods) {
            names.add(method.get("name").asText());
        }
        return names;
    }
}
