package dev.eugene.publicationexporter.buildfromreview;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.release.NullReleaseOutputStore;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildFromReviewHandlerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void noApprovedSnapshotIsBlockedBeforeAnyOutputWrite() {
        BuildFromReviewHandler handler = new BuildFromReviewHandler(
                ApprovedSnapshotWorkspace.createNull(), ReleaseOutputStore.createNull());

        ReleaseResult result = handler.buildFromReview(IDENTITY);

        assertFalse(result.ok());
        assertEquals("No approved snapshot exists to release.", result.message());
    }

    @Test
    void approvedSnapshotIsReleasedWithMatchingApprovedAndOutputHashes() {
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        String ruHash = ContentHash.sha256Hex("# My Essay");
        String enHash = ContentHash.sha256Hex("# My Essay (EN)");
        approvedSnapshotWorkspace.install(IDENTITY, "# My Essay", "# My Essay (EN)",
                ReferenceMap.empty(IDENTITY, ruHash, enHash));
        NullReleaseOutputStore releaseOutputStore = new NullReleaseOutputStore();
        BuildFromReviewHandler handler = new BuildFromReviewHandler(approvedSnapshotWorkspace, releaseOutputStore);

        ReleaseResult result = handler.buildFromReview(IDENTITY);

        assertTrue(result.ok());
        assertEquals(IDENTITY, result.identity());
        assertEquals(1, result.provenance().contractEdition());
        assertEquals(ruHash, result.provenance().approvedRuHash());
        assertEquals(enHash, result.provenance().approvedEnHash());
        assertEquals(ruHash, result.provenance().outputRuHash());
        assertEquals(enHash, result.provenance().outputEnHash());
        assertEquals(0, result.provenance().activationCount());
        assertEquals(0, result.provenance().deactivationCount());
        assertTrue(releaseOutputStore.installed().containsKey(IDENTITY));
        assertEquals("# My Essay", releaseOutputStore.installed().get(IDENTITY).ruBody());
        assertEquals("# My Essay (EN)", releaseOutputStore.installed().get(IDENTITY).enBody());
    }

    @Test
    void anExistingCandidateIsNeverConsultedOrReflectedInOutput() {
        // BuildFromReviewHandler takes no CandidateWorkspace collaborator at all — REL-01's
        // "candidate has no release authority" is enforced by the constructor's own shape,
        // not by a runtime check. There is no candidate parameter to ignore.
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        approvedSnapshotWorkspace.install(IDENTITY, "approved RU", "approved EN",
                ReferenceMap.empty(IDENTITY,
                        ContentHash.sha256Hex("approved RU"), ContentHash.sha256Hex("approved EN")));
        NullReleaseOutputStore releaseOutputStore = new NullReleaseOutputStore();
        BuildFromReviewHandler handler = new BuildFromReviewHandler(approvedSnapshotWorkspace, releaseOutputStore);

        handler.buildFromReview(IDENTITY);

        assertEquals("approved RU", releaseOutputStore.installed().get(IDENTITY).ruBody());
    }

    @Test
    void approvedSnapshotLookupIoFailureReturnsBlockedResult() {
        BuildFromReviewHandler handler = new BuildFromReviewHandler(
                approvedSnapshotWorkspaceThrowing(new UncheckedIOException(
                        new IOException("approved snapshot read unavailable"))),
                ReleaseOutputStore.createNull());

        ReleaseResult result = handler.buildFromReview(IDENTITY);

        assertFalse(result.ok());
        assertEquals("Approved snapshot lookup failed: approved snapshot read unavailable", result.message());
    }

    @Test
    void approvedSnapshotReleaseIoFailureReturnsBlockedResult() {
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        approvedSnapshotWorkspace.install(IDENTITY, "RU", "EN",
                ReferenceMap.empty(IDENTITY, ContentHash.sha256Hex("RU"), ContentHash.sha256Hex("EN")));
        BuildFromReviewHandler handler = new BuildFromReviewHandler(
                approvedSnapshotWorkspace,
                releaseOutputStoreThrowing(new UncheckedIOException(
                        new IOException("release output store unavailable"))));

        ReleaseResult result = handler.buildFromReview(IDENTITY);

        assertFalse(result.ok());
        assertEquals("Release installation failed: release output store unavailable", result.message());
    }

    @Test
    void aSecondReleaseAttemptForTheSameIdentityIsBlocked() {
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        approvedSnapshotWorkspace.install(IDENTITY, "RU", "EN",
                ReferenceMap.empty(IDENTITY, ContentHash.sha256Hex("RU"), ContentHash.sha256Hex("EN")));
        NullReleaseOutputStore releaseOutputStore = new NullReleaseOutputStore();
        BuildFromReviewHandler handler = new BuildFromReviewHandler(approvedSnapshotWorkspace, releaseOutputStore);
        handler.buildFromReview(IDENTITY);

        ReleaseResult second = handler.buildFromReview(IDENTITY);

        assertFalse(second.ok());
        assertEquals("A release already exists at this output root; replacing it is not yet supported.",
                second.message());
    }

    private static ApprovedSnapshotWorkspace approvedSnapshotWorkspaceThrowing(RuntimeException failure) {
        return new ApprovedSnapshotWorkspace() {
            @Override
            public void install(PublicationIdentity identity,
                                String ruBody,
                                String enBody,
                                dev.eugene.publicationexporter.reference.ReferenceMap referenceMap) {
                // no-op: this test double exercises only the read side
            }

            @Override
            public Optional<CandidatePaths> find(PublicationIdentity identity) {
                return Optional.empty();
            }

            @Override
            public Optional<dev.eugene.publicationexporter.candidate.CandidateSnapshot> read(PublicationIdentity identity) {
                throw failure;
            }
        };
    }

    private static ReleaseOutputStore releaseOutputStoreThrowing(RuntimeException failure) {
        return new ReleaseOutputStore() {
            @Override
            public void install(PublicationIdentity identity,
                                String ruBody,
                                String enBody,
                                dev.eugene.publicationexporter.release.ReleaseProvenance provenance) {
                throw failure;
            }
        };
    }
}
