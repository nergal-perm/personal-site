package dev.eugene.publicationexporter.refresh;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.NullWorkflowStatusEditor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RefreshPublicationQueueHandlerTest {

    private static final VaultRelativePath STALE_SCALAR_NOTE = VaultRelativePath.of("blog/stale-scalar.md");
    private static final VaultRelativePath UP_TO_DATE_NOTE = VaultRelativePath.of("blog/up-to-date.md");
    private static final VaultRelativePath MALFORMED_NOTE = VaultRelativePath.of("blog/malformed.md");
    private static final VaultRelativePath NOT_PUBLISHED_NOTE = VaultRelativePath.of("blog/draft.md");

    @Test
    void refreshCorrectsStaleScalarsCountsUnchangedAndCountsUncertain() {
        String staleScalarSource = essaySource("stale-scalar", "workflowStatus: not_prepared");
        String upToDateSource = essaySource("up-to-date", "workflowStatus: ready_for_review");
        String malformedSource = "---\npublish: true\npublicId: malformed\n---\nMissing required fields.";
        String draftSource = "---\npublish: false\npublicId: draft\n---\nNot a candidate.";

        Map<VaultRelativePath, String> notes = new LinkedHashMap<>();
        notes.put(STALE_SCALAR_NOTE, staleScalarSource);
        notes.put(UP_TO_DATE_NOTE, upToDateSource);
        notes.put(MALFORMED_NOTE, malformedSource);
        notes.put(NOT_PUBLISHED_NOTE, draftSource);
        VaultReader vaultReader = VaultReader.createNull(notes);

        CandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        PublicationIdentity upToDateIdentity = PublicationIdentity.of("blog", "essay", "up-to-date");
        candidateWorkspace.install(upToDateIdentity, "RU", "EN", "RU title", "EN title",
                "RU description.", "EN description.", ReferenceMap.empty(upToDateIdentity,
                        ContentHash.sha256Hex("RU"), ContentHash.sha256Hex("EN"),
                        ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("RU description."), ContentHash.sha256Hex("EN description.")));
        // With neither a candidate nor an approved snapshot, stale-scalar would classify as not_prepared
        // and already match its persisted scalar. An approved snapshot makes ready_to_publish the decisive
        // current classification, so the stale not_prepared scalar must be updated.
        PublicationIdentity staleScalarIdentity = PublicationIdentity.of("blog", "essay", "stale-scalar");
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        approvedSnapshotWorkspace.install(staleScalarIdentity, "RU", "EN", "RU title", "EN title",
                "RU description.", "EN description.", ReferenceMap.empty(staleScalarIdentity,
                        ContentHash.sha256Hex("RU"), ContentHash.sha256Hex("EN"),
                        ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("RU description."), ContentHash.sha256Hex("EN description.")));

        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(notes);
        RefreshPublicationQueueHandler handler = new RefreshPublicationQueueHandler(
                candidateWorkspace, approvedSnapshotWorkspace, editor);

        BridgeResponse response = handler.refresh(vaultReader);

        assertEquals("queue_refreshed", response.status());
        assertEquals(1, response.updatedCount());
        assertEquals(1, response.unchangedCount());
        // Admission failure is the observed metadata_blocked outcome, not uncertain classification evidence.
        // The malformed note is therefore excluded from all three queue counts; the draft is not discovered.
        assertEquals(0, response.uncertainCount());
        assertEquals("ready_to_publish", editor.currentValue(STALE_SCALAR_NOTE, "workflowStatus"));
    }

    private String essaySource(String publicId, String workflowStatusLine) {
        return "---\npublish: true\npublicCollection: blog\npublicContentType: essay\npublicId: " + publicId
                + "\nid: id-" + publicId + "\ntitle: Title\ndescription: A description.\n"
                + workflowStatusLine + "\n---\nBody.";
    }
}
