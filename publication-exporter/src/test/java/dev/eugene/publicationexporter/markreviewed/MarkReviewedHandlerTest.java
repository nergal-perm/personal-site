package dev.eugene.publicationexporter.markreviewed;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceConfinementException;
import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspaceConfinementException;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

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
            title: My Essay
            description: A valid description.
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
        candidateWorkspace.install(identity, ESSAY_BODY, "EN body", "My Essay", "EN title",
                "A valid description.", "EN description.", ReferenceMap.empty(
                        identity, ruHash, enHash,
                        ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("A valid description."),
                        ContentHash.sha256Hex("EN description.")));
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        MarkReviewedHandler handler = new MarkReviewedHandler(candidateWorkspace, approvedSnapshotWorkspace);

        BridgeResponse response = handler.markReviewed(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("ready_to_publish", response.status());
        assertEquals("my-essay", response.identity().publicId());
        CandidateSnapshot approved = approvedSnapshotWorkspace.read(identity).orElseThrow();
        assertEquals("My Essay", approved.ruTitle());
        assertEquals("EN title", approved.enTitle());
        assertEquals("A valid description.", approved.ruDescription());
        assertEquals("EN description.", approved.enDescription());
    }

    @Test
    void alreadyApprovedIsBlocked() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        String ruHash = ContentHash.sha256Hex(ESSAY_BODY);
        String enHash = ContentHash.sha256Hex("EN body");
        ReferenceMap referenceMap = ReferenceMap.empty(
                identity, ruHash, enHash,
                ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("EN title"),
                ContentHash.sha256Hex("A valid description."), ContentHash.sha256Hex("EN description."));
        candidateWorkspace.install(identity, ESSAY_BODY, "EN body", "My Essay", "EN title",
                "A valid description.", "EN description.", referenceMap);
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        approvedSnapshotWorkspace.install(identity, ESSAY_BODY, "EN body", "My Essay", "EN title",
                "A valid description.", "EN description.", referenceMap);
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
        candidateWorkspace.install(identity, "# An old version of My Essay", "EN body", "My Essay", "EN title",
                "A valid description.", "EN description", ReferenceMap.empty(
                        identity, staleRuHash, enHash,
                        ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("A valid description."), ContentHash.sha256Hex("EN description")));
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
        candidateWorkspace.install(identity, ESSAY_BODY, "tampered EN body", "My Essay", "EN title",
                "A valid description.", "EN description", ReferenceMap.empty(
                        identity, ruHash, staleEnHash,
                        ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("A valid description."), ContentHash.sha256Hex("EN description")));
        MarkReviewedHandler handler = new MarkReviewedHandler(
                candidateWorkspace, ApprovedSnapshotWorkspace.createNull());

        BridgeResponse response = handler.markReviewed(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("stale", response.status());
    }

    @Test
    void sourceTitleChangedSinceCandidateWasPreparedIsStale() {
        ReferenceMap referenceMap = matchingReferenceMap(
                ESSAY_BODY, "EN body", "An old title", "EN title",
                "A valid description.", "EN description.");

        BridgeResponse response = markReviewedCandidate(
                ESSAY_BODY, "EN body", "An old title", "EN title",
                "A valid description.", "EN description.", referenceMap);

        assertStale(response, "Source note title has changed since the candidate was prepared.");
    }

    @Test
    void sourceDescriptionChangedSinceCandidateWasPreparedIsStale() {
        ReferenceMap referenceMap = matchingReferenceMap(
                ESSAY_BODY, "EN body", "My Essay", "EN title",
                "An old description.", "EN description.");

        BridgeResponse response = markReviewedCandidate(
                ESSAY_BODY, "EN body", "My Essay", "EN title",
                "An old description.", "EN description.", referenceMap);

        assertStale(response, "Source note description has changed since the candidate was prepared.");
    }

    @Test
    void candidateRussianBodyTamperedWithSincePreparationIsStale() {
        ReferenceMap referenceMap = matchingReferenceMap(
                ESSAY_BODY, "EN body", "My Essay", "EN title",
                "A valid description.", "EN description.");

        BridgeResponse response = markReviewedCandidate(
                "Tampered Russian body", "EN body", "My Essay", "EN title",
                "A valid description.", "EN description.", referenceMap);

        assertStale(response, "Candidate Russian body has changed since it was prepared.");
    }

    @Test
    void candidateRussianTitleTamperedWithSincePreparationIsStale() {
        ReferenceMap referenceMap = matchingReferenceMap(
                ESSAY_BODY, "EN body", "My Essay", "EN title",
                "A valid description.", "EN description.");

        BridgeResponse response = markReviewedCandidate(
                ESSAY_BODY, "EN body", "Tampered Russian title", "EN title",
                "A valid description.", "EN description.", referenceMap);

        assertStale(response, "Candidate Russian title has changed since it was prepared.");
    }

    @Test
    void candidateEnglishTitleTamperedWithSincePreparationIsStale() {
        ReferenceMap referenceMap = matchingReferenceMap(
                ESSAY_BODY, "EN body", "My Essay", "EN title",
                "A valid description.", "EN description.");

        BridgeResponse response = markReviewedCandidate(
                ESSAY_BODY, "EN body", "My Essay", "Tampered English title",
                "A valid description.", "EN description.", referenceMap);

        assertStale(response, "Candidate English title has changed since it was prepared.");
    }

    @Test
    void candidateRussianDescriptionTamperedWithSincePreparationIsStale() {
        ReferenceMap referenceMap = matchingReferenceMap(
                ESSAY_BODY, "EN body", "My Essay", "EN title",
                "A valid description.", "EN description.");

        BridgeResponse response = markReviewedCandidate(
                ESSAY_BODY, "EN body", "My Essay", "EN title",
                "Tampered Russian description.", "EN description.", referenceMap);

        assertStale(response, "Candidate Russian description has changed since it was prepared.");
    }

    @Test
    void candidateEnglishDescriptionTamperedWithSincePreparationIsStale() {
        ReferenceMap referenceMap = matchingReferenceMap(
                ESSAY_BODY, "EN body", "My Essay", "EN title",
                "A valid description.", "EN description.");

        BridgeResponse response = markReviewedCandidate(
                ESSAY_BODY, "EN body", "My Essay", "EN title",
                "A valid description.", "Tampered English description.", referenceMap);

        assertStale(response, "Candidate English description has changed since it was prepared.");
    }

    @Test
    void candidateReadConfinementFailureReturnsBlockedResponse() {
        MarkReviewedHandler handler = new MarkReviewedHandler(
                candidateWorkspaceThrowing(candidateConfinementFailure()),
                ApprovedSnapshotWorkspace.createNull());

        BridgeResponse response = handler.markReviewed(validEssayPath(), validEssayReader());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals(2, response.schemaVersion());
        assertEquals("mark-reviewed", response.command());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("Candidate lookup failed"));
        assertTrue(response.diagnostics().get(0).blocking());
    }

    @Test
    void candidateReadIoFailureReturnsBlockedResponse() {
        MarkReviewedHandler handler = new MarkReviewedHandler(
                candidateWorkspaceThrowing(
                        new UncheckedIOException(new IOException("candidate directory unavailable"))),
                ApprovedSnapshotWorkspace.createNull());

        BridgeResponse response = handler.markReviewed(validEssayPath(), validEssayReader());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Candidate lookup failed: candidate directory unavailable",
                response.diagnostics().get(0).message());
    }

    @Test
    void approvedSnapshotLookupConfinementFailureReturnsBlockedResponse() {
        MarkReviewedHandler handler = new MarkReviewedHandler(
                exactCandidateWorkspace(),
                approvedSnapshotWorkspaceThrowing(approvedSnapshotConfinementFailure()));

        BridgeResponse response = handler.markReviewed(validEssayPath(), validEssayReader());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals(2, response.schemaVersion());
        assertEquals("mark-reviewed", response.command());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("Approved snapshot lookup failed"));
        assertTrue(response.diagnostics().get(0).blocking());
    }

    @Test
    void approvedSnapshotLookupIoFailureReturnsBlockedResponse() {
        MarkReviewedHandler handler = new MarkReviewedHandler(
                exactCandidateWorkspace(),
                approvedSnapshotWorkspaceThrowing(
                        new UncheckedIOException(new IOException("approved directory unavailable"))));

        BridgeResponse response = handler.markReviewed(validEssayPath(), validEssayReader());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Approved snapshot lookup failed: approved directory unavailable",
                response.diagnostics().get(0).message());
    }

    private static CandidateWorkspace exactCandidateWorkspace() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        workspace.install(identity, ESSAY_BODY, "EN body", "My Essay", "EN title",
                "A valid description.", "EN description", ReferenceMap.empty(
                        identity, ContentHash.sha256Hex(ESSAY_BODY), ContentHash.sha256Hex("EN body"),
                        ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("A valid description."), ContentHash.sha256Hex("EN description")));
        return workspace;
    }

    private static ReferenceMap matchingReferenceMap(
            String ruBody,
            String enBody,
            String ruTitle,
            String enTitle,
            String ruDescription,
            String enDescription) {
        return ReferenceMap.empty(
                PublicationIdentity.of("blog", "essay", "my-essay"),
                ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody),
                ContentHash.sha256Hex(ruTitle), ContentHash.sha256Hex(enTitle),
                ContentHash.sha256Hex(ruDescription), ContentHash.sha256Hex(enDescription));
    }

    private static BridgeResponse markReviewedCandidate(
            String ruBody,
            String enBody,
            String ruTitle,
            String enTitle,
            String ruDescription,
            String enDescription,
            ReferenceMap referenceMap) {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        candidateWorkspace.install(identity, ruBody, enBody, ruTitle, enTitle,
                ruDescription, enDescription, referenceMap);
        MarkReviewedHandler handler = new MarkReviewedHandler(
                candidateWorkspace, ApprovedSnapshotWorkspace.createNull());
        return handler.markReviewed(validEssayPath(), validEssayReader());
    }

    private static void assertStale(BridgeResponse response, String expectedMessage) {
        assertFalse(response.ok());
        assertEquals("stale", response.status());
        assertEquals(expectedMessage, response.diagnostics().get(0).message());
    }

    private static CandidateWorkspace candidateWorkspaceThrowing(RuntimeException failure) {
        return new CandidateWorkspace() {
            @Override
            public void install(
                    PublicationIdentity identity,
                    String ruBody,
                    String enBody,
                    String ruTitle,
                    String enTitle,
                    String ruDescription,
                    String enDescription,
                    ReferenceMap referenceMap) {
                // no-op: this test double exercises only the read side
            }

            @Override
            public Optional<CandidatePaths> find(PublicationIdentity identity) {
                throw failure;
            }

            @Override
            public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
                throw failure;
            }
        };
    }

    private static ApprovedSnapshotWorkspace approvedSnapshotWorkspaceThrowing(RuntimeException failure) {
        return new ApprovedSnapshotWorkspace() {
            @Override
            public void install(
                    PublicationIdentity identity,
                    String ruBody,
                    String enBody,
                    String ruTitle,
                    String enTitle,
                    String ruDescription,
                    String enDescription,
                    ReferenceMap referenceMap) {
                // no-op: this test double exercises only the lookup side
            }

            @Override
            public Optional<CandidatePaths> find(PublicationIdentity identity) {
                throw failure;
            }

            @Override
            public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
                throw failure;
            }
        };
    }

    private static CandidateWorkspaceConfinementException candidateConfinementFailure() {
        CandidateWorkspace realWorkspace = CandidateWorkspace.create(Path.of("/review"));
        PublicationIdentity escapingIdentity = PublicationIdentity.of("../..", "essay", "outside");
        try {
            realWorkspace.read(escapingIdentity);
        } catch (CandidateWorkspaceConfinementException failure) {
            return failure;
        }
        throw new AssertionError("Expected an escaping identity to fail candidate-workspace confinement");
    }

    private static ApprovedSnapshotWorkspaceConfinementException approvedSnapshotConfinementFailure() {
        ApprovedSnapshotWorkspace realWorkspace = ApprovedSnapshotWorkspace.create(Path.of("/review"));
        PublicationIdentity escapingIdentity = PublicationIdentity.of("../..", "essay", "outside");
        try {
            realWorkspace.find(escapingIdentity);
        } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
            return failure;
        }
        throw new AssertionError("Expected an escaping identity to fail approved-workspace confinement");
    }

    private static VaultRelativePath validEssayPath() {
        return VaultRelativePath.of("blog/my-essay.md");
    }

    private static VaultReader validEssayReader() {
        return VaultReader.createNull(Map.of(validEssayPath(), VALID_ESSAY));
    }
}
