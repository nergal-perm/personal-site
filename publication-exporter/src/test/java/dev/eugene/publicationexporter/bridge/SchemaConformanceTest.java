package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
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
        InspectPublicationHandler handler = new InspectPublicationHandler(CandidateWorkspace.createNull());
        VaultReader vaultReader = VaultReader.createNull();
        BridgeResponse response = handler.inspect(
                VaultRelativePath.of("../../etc/passwd.md"), vaultReader);

        assertConformsToSchemaV2(response);
    }

    @Test
    void validEssayResponseConformsToSchemaV2() throws Exception {
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

        InspectPublicationHandler handler = new InspectPublicationHandler(CandidateWorkspace.createNull());
        BridgeResponse response = handler.inspect(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("not_prepared", response.status());

        assertConformsToSchemaV2(response);
    }

    @Test
    void preparedResponseConformsToSchemaV2() throws Exception {
        BridgeResponse response = BridgeResponse.prepared(
                "prepare", dev.eugene.publicationexporter.bridge.PublicationIdentity.of("blog", "essay", "my-essay"));

        assertConformsToSchemaV2(response);
    }

    @Test
    void translationFailedResponseConformsToSchemaV2() throws Exception {
        BridgeResponse response = BridgeResponse.translationFailed("prepare",
                dev.eugene.publicationexporter.bridge.Diagnostic.blocking("candidate", "worker crashed"));

        assertConformsToSchemaV2(response);
    }

    @Test
    void essayInspectedResponseWithReviewPlanConformsToSchemaV2() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        CandidatePaths candidatePaths = CandidatePaths.of(
                Path.of("/review/blog/my-essay/candidate/ru.md"),
                Path.of("/review/blog/my-essay/candidate/en.md"));
        BridgeResponse response = BridgeResponse.essayInspected(
                "inspect-publication", "ready_for_review", identity,
                "ready", "absent", "absent", "absent", ReviewPlan.firstPublication(candidatePaths));

        assertConformsToSchemaV2(response);
    }

    private void assertConformsToSchemaV2(BridgeResponse response) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        try (var schemaStream = Files.newInputStream(SCHEMA_PATH)) {
            JsonSchema schema = factory.getSchema(schemaStream);
            JsonNode responseNode = mapper.valueToTree(response);
            Set<ValidationMessage> errors = schema.validate(responseNode);

            assertTrue(errors.isEmpty(), () -> "Schema violations: " + errors);
        }
    }
}
