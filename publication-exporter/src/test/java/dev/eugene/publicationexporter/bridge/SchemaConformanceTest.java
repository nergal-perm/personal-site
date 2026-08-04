package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.eugene.publicationexporter.inspect.InspectPublicationHandler;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemaConformanceTest {

    private static final Path SCHEMA_PATH = Path.of("../bridge-contract/schema-v2.json");

    @Test
    void blockedResponseConformsToSchemaV2() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        JsonSchema schema = factory.getSchema(Files.newInputStream(SCHEMA_PATH));

        InspectPublicationHandler handler = new InspectPublicationHandler();
        VaultReader vaultReader = VaultReader.createNull();
        BridgeResponse response = handler.inspect(
                VaultRelativePath.of("../../etc/passwd.md"), vaultReader);

        JsonNode responseNode = mapper.valueToTree(response);
        Set<ValidationMessage> errors = schema.validate(responseNode);

        assertTrue(errors.isEmpty(), () -> "Schema violations: " + errors);
    }

    @Test
    void validEssayResponseConformsToSchemaV2() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        JsonSchema schema = factory.getSchema(Files.newInputStream(SCHEMA_PATH));

        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        String validEssay = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                ---
                """;
        VaultReader vaultReader = VaultReader.createNull(java.util.Map.of(path, validEssay));

        InspectPublicationHandler handler = new InspectPublicationHandler();
        BridgeResponse response = handler.inspect(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("not_prepared", response.status());

        JsonNode responseNode = mapper.valueToTree(response);
        Set<ValidationMessage> errors = schema.validate(responseNode);

        assertTrue(errors.isEmpty(), () -> "Schema violations: " + errors);
    }
}
