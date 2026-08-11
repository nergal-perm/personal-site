package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.inspect.InspectPublicationHandler;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.prepare.RussianDiff;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaConformanceTest {

    private static final Path SCHEMA_PATH = Path.of("../bridge-contract/schema-v2.json");

    @Test
    void blockedResponseConformsToSchemaV2() throws Exception {
        InspectPublicationHandler handler = new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()),
                CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.createNull());
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
                title: My Essay
                description: A valid description.
                ---
                """;
        VaultReader vaultReader = VaultReader.createNull(java.util.Map.of(path, validEssay));

        InspectPublicationHandler handler = new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()),
                CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.createNull());
        BridgeResponse response = handler.inspect(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("not_prepared", response.status());

        assertConformsToSchemaV2(response);
    }

    @Test
    void readyToPublishInspectionResponseConformsToSchemaV2() throws Exception {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        BridgeResponse response = BridgeResponse.essayInspected(
                "inspect-publication", "ready_to_publish", identity,
                "absent", "ready", "absent", "absent", null);

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
        assertConformsToSchemaV2(readyForReviewResponse());
    }

    @Test
    void reversedReviewTargetsDoNotConformToSchemaV2() throws Exception {
        ObjectNode response = responseNode(readyForReviewResponse());
        ArrayNode targets = (ArrayNode) response.at("/reviewPlan/targets");
        JsonNode ruTarget = targets.get(0).deepCopy();
        JsonNode enTarget = targets.get(1).deepCopy();
        targets.set(0, enTarget);
        targets.set(1, ruTarget);

        assertDoesNotConformToSchemaV2(response);
    }

    @Test
    void relativeProposedPathDoesNotConformToSchemaV2() throws Exception {
        ObjectNode response = responseNode(readyForReviewResponse());
        ((ObjectNode) response.at("/reviewPlan/targets/0"))
                .put("proposedPath", "review/blog/my-essay/candidate/ru.md");

        assertDoesNotConformToSchemaV2(response);
    }

    @Test
    void absentBaselineWithPublishedPathDoesNotConformToSchemaV2() throws Exception {
        ObjectNode response = responseNode(readyForReviewResponse());
        ((ObjectNode) response.at("/reviewPlan/targets/0"))
                .put("publishedPath", "/review/blog/my-essay/published/ru.md");

        assertDoesNotConformToSchemaV2(response);
    }

    @Test
    void readyForReviewInspectionWithoutReviewPlanDoesNotConformToSchemaV2() throws Exception {
        ObjectNode response = responseNode(readyForReviewResponse());
        response.remove("reviewPlan");

        assertDoesNotConformToSchemaV2(response);
    }

    @Test
    void changedReviewPlanWithNonEmptyDiffConformsToSchemaV2() throws Exception {
        assertConformsToSchemaV2(changedReadyForReviewResponse());
    }

    @Test
    void changedReviewPlanWithoutDiffDoesNotConformToSchemaV2() throws Exception {
        ObjectNode response = responseNode(changedReadyForReviewResponse());
        ((ObjectNode) response.get("reviewPlan")).remove("diff");

        assertDoesNotConformToSchemaV2(response);
    }

    @Test
    void changedReviewPlanWithEmptyDiffDoesNotConformToSchemaV2() throws Exception {
        ObjectNode response = responseNode(changedReadyForReviewResponse());
        ((ObjectNode) response.get("reviewPlan")).putArray("diff");

        assertDoesNotConformToSchemaV2(response);
    }

    @Test
    void approvedResponseConformsToSchemaV2() throws Exception {
        BridgeResponse response = BridgeResponse.approved(
                "mark-reviewed", PublicationIdentity.of("blog", "essay", "my-essay"));

        assertConformsToSchemaV2(response);
    }

    @Test
    void staleResponseConformsToSchemaV2() throws Exception {
        BridgeResponse response = BridgeResponse.stale("mark-reviewed",
                Diagnostic.blocking("candidate", "Source note has changed since the candidate was prepared."));

        assertConformsToSchemaV2(response);
    }

    @Test
    void queueRefreshedResponseConformsToSchemaV2() throws Exception {
        BridgeResponse response = BridgeResponse.queueRefreshed("refresh-publication-queue", 2, 5, 1);

        assertConformsToSchemaV2(response);
    }

    @Test
    void queueRefreshedResponseWithoutCountsDoesNotConformToSchemaV2() throws Exception {
        ObjectNode response = responseNode(BridgeResponse.queueRefreshed("refresh-publication-queue", 2, 5, 1));
        response.remove("updatedCount");

        assertDoesNotConformToSchemaV2(response);
    }

    private BridgeResponse readyForReviewResponse() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        CandidatePaths candidatePaths = CandidatePaths.of(
                Path.of("/review/blog/my-essay/candidate/ru.md"),
                Path.of("/review/blog/my-essay/candidate/en.md"));
        return BridgeResponse.essayInspected(
                "inspect-publication", "ready_for_review", identity,
                "ready", "absent", "absent", "absent", ReviewPlan.firstPublication(
                        candidatePaths, "RU title", "EN title", "RU description.", "EN description."));
    }

    private BridgeResponse changedReadyForReviewResponse() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        CandidatePaths candidatePaths = CandidatePaths.of(
                Path.of("/review/blog/my-essay/candidate/ru.md"),
                Path.of("/review/blog/my-essay/candidate/en.md"));
        RussianDiff diff = RussianDiff.between(
                "RU body", "Old RU title", "RU description.",
                "RU body", "New RU title", "RU description.");
        return BridgeResponse.essayInspected(
                "inspect-publication", "ready_for_review", identity,
                "ready", "ready", "absent", "absent", ReviewPlan.changedPublication(
                        candidatePaths, "New RU title", "EN title",
                        "RU description.", "EN description.", diff));
    }

    private void assertConformsToSchemaV2(BridgeResponse response) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertConformsToSchemaV2(mapper.valueToTree(response));
    }

    private ObjectNode responseNode(BridgeResponse response) {
        return new ObjectMapper().valueToTree(response);
    }

    private void assertConformsToSchemaV2(JsonNode responseNode) throws Exception {
        Set<ValidationMessage> errors = validate(responseNode);

        assertTrue(errors.isEmpty(), () -> "Schema violations: " + errors);
    }

    private void assertDoesNotConformToSchemaV2(JsonNode responseNode) throws Exception {
        Set<ValidationMessage> errors = validate(responseNode);

        assertFalse(errors.isEmpty(), "Expected schema violations");
    }

    private Set<ValidationMessage> validate(JsonNode responseNode) throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        try (var schemaStream = Files.newInputStream(SCHEMA_PATH)) {
            JsonSchema schema = factory.getSchema(schemaStream);
            return schema.validate(responseNode);
        }
    }
}
