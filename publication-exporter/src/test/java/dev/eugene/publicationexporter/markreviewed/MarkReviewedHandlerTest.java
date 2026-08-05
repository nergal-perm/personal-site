package dev.eugene.publicationexporter.markreviewed;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkReviewedHandlerTest {

    private static final String VALID_ESSAY = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            ---
            # My Essay""";

    private static final String ESSAY_BODY = "# My Essay";

    @Test
    void unsafePathIsBlocked() {
        MarkReviewedHandler handler = new MarkReviewedHandler(
                CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.createNull());

        BridgeResponse response = handler.markReviewed(
                VaultRelativePath.of("../../etc/passwd.md"), VaultReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
    }

    @Test
    void noCandidateIsBlocked() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        MarkReviewedHandler handler = new MarkReviewedHandler(
                CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.createNull());

        BridgeResponse response = handler.markReviewed(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("No candidate exists to approve.", response.diagnostics().get(0).message());
    }

    @Test
    void exactCandidateIsApproved() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        String ruHash = ContentHash.sha256Hex(ESSAY_BODY);
        String enHash = ContentHash.sha256Hex("EN body");
        candidateWorkspace.install(identity, ESSAY_BODY, "EN body", ReferenceMap.empty(identity, ruHash, enHash));
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        MarkReviewedHandler handler = new MarkReviewedHandler(candidateWorkspace, approvedSnapshotWorkspace);

        BridgeResponse response = handler.markReviewed(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("ready_to_publish", response.status());
        assertEquals("my-essay", response.identity().publicId());
        assertTrue(approvedSnapshotWorkspace.find(identity).isPresent());
    }

    @Test
    void alreadyApprovedIsBlocked() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        String ruHash = ContentHash.sha256Hex(ESSAY_BODY);
        String enHash = ContentHash.sha256Hex("EN body");
        ReferenceMap referenceMap = ReferenceMap.empty(identity, ruHash, enHash);
        candidateWorkspace.install(identity, ESSAY_BODY, "EN body", referenceMap);
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        approvedSnapshotWorkspace.install(identity, ESSAY_BODY, "EN body", referenceMap);
        MarkReviewedHandler handler = new MarkReviewedHandler(candidateWorkspace, approvedSnapshotWorkspace);

        BridgeResponse response = handler.markReviewed(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("An approved snapshot already exists; replacing it is not yet supported.",
                response.diagnostics().get(0).message());
    }

    @Test
    void sourceChangedSinceCandidateWasPreparedIsStale() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        // Candidate was prepared from a DIFFERENT body than the source note now has.
        String staleRuHash = ContentHash.sha256Hex("# An old version of My Essay");
        String enHash = ContentHash.sha256Hex("EN body");
        candidateWorkspace.install(identity, "# An old version of My Essay", "EN body",
                ReferenceMap.empty(identity, staleRuHash, enHash));
        MarkReviewedHandler handler = new MarkReviewedHandler(
                candidateWorkspace, ApprovedSnapshotWorkspace.createNull());

        BridgeResponse response = handler.markReviewed(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("stale", response.status());
    }

    @Test
    void candidateFileTamperedWithSincePreparationIsStale() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        // referenceMap records a hash for DIFFERENT English content than what's actually installed —
        // simulates en.md having been overwritten after prepare recorded its hash.
        String ruHash = ContentHash.sha256Hex(ESSAY_BODY);
        String staleEnHash = ContentHash.sha256Hex("original EN body prepare recorded");
        candidateWorkspace.install(identity, ESSAY_BODY, "tampered EN body",
                ReferenceMap.empty(identity, ruHash, staleEnHash));
        MarkReviewedHandler handler = new MarkReviewedHandler(
                candidateWorkspace, ApprovedSnapshotWorkspace.createNull());

        BridgeResponse response = handler.markReviewed(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("stale", response.status());
    }
}
