package dev.eugene.publicationexporter.buildfromreview;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceConfinementException;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceStateException;
import dev.eugene.publicationexporter.bridge.IoFailureMessages;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.Occurrence;
import dev.eugene.publicationexporter.release.ApprovedTargetRegistry;
import dev.eugene.publicationexporter.release.OccurrenceMarkerResolver;
import dev.eugene.publicationexporter.release.OccurrenceResolution;
import dev.eugene.publicationexporter.release.ReleaseAlreadyExistsException;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import dev.eugene.publicationexporter.release.ReleaseOutputStoreConfinementException;
import dev.eugene.publicationexporter.release.ReleaseProvenance;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class BuildFromReviewHandler {

    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final ReleaseOutputStore releaseOutputStore;

    public BuildFromReviewHandler(ApprovedSnapshotWorkspace approvedSnapshotWorkspace, ReleaseOutputStore releaseOutputStore) {
        this.approvedSnapshotWorkspace = Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.releaseOutputStore = Objects.requireNonNull(releaseOutputStore, "releaseOutputStore");
    }

    public ReleaseResult buildFromReview(PublicationIdentity identity) {
        Optional<CandidateSnapshot> approved;
        try {
            approved = approvedSnapshotWorkspace.read(identity);
        } catch (UncheckedIOException failure) {
            return ReleaseResult.blocked(IoFailureMessages.describe("Approved snapshot lookup failed", failure));
        } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
            return ReleaseResult.blocked("Approved snapshot lookup failed: " + failure.getMessage());
        } catch (ApprovedSnapshotWorkspaceStateException failure) {
            return ReleaseResult.blocked("Approved snapshot lookup failed: " + failure.getMessage());
        }
        if (approved.isEmpty()) {
            return noApprovedSnapshotResult();
        }
        return releaseApprovedSnapshot(identity, approved.get());
    }

    private ReleaseResult releaseApprovedSnapshot(PublicationIdentity identity, CandidateSnapshot approved) {
        ResolvedRelease resolvedRelease = resolveApprovedSnapshot(identity, approved);
        try {
            releaseOutputStore.install(
                    identity, resolvedRelease.ruBody(), resolvedRelease.enBody(), resolvedRelease.provenance());
        } catch (ReleaseAlreadyExistsException raceLoser) {
            return alreadyReleasedResult();
        } catch (ReleaseOutputStoreConfinementException failure) {
            return ReleaseResult.blocked("Release installation failed: " + failure.getMessage());
        } catch (UncheckedIOException failure) {
            return ReleaseResult.blocked(IoFailureMessages.describe("Release installation failed", failure));
        }
        return ReleaseResult.released(identity, resolvedRelease.provenance());
    }

    private ResolvedRelease resolveApprovedSnapshot(PublicationIdentity identity, CandidateSnapshot approved) {
        List<Occurrence> occurrences = approved.referenceMap().occurrences();
        ApprovedTargetRegistry registry = ApprovedTargetRegistry.forOccurrences(
                occurrences, approvedSnapshotWorkspace);
        OccurrenceResolution ruResolution = OccurrenceMarkerResolver.resolve(
                approved.ruBody(), registry, occurrences, "ru");
        OccurrenceResolution enResolution = OccurrenceMarkerResolver.resolve(
                approved.enBody(), registry, occurrences, "en");
        // RU is the canonical count: both locale bodies resolve the same logical occurrences.
        ReleaseProvenance provenance = provenanceFor(identity, approved,
                ruResolution.activatedCount(), ruResolution.deactivatedCount());
        return new ResolvedRelease(ruResolution.body(), enResolution.body(), provenance);
    }

    private static ReleaseProvenance provenanceFor(
            PublicationIdentity identity, CandidateSnapshot approved,
            int activationCount, int deactivationCount) {
        String outputRuHash = ContentHash.sha256Hex(approved.ruBody());
        String outputEnHash = ContentHash.sha256Hex(approved.enBody());
        return ReleaseProvenance.of(identity,
                approved.referenceMap().ruHash(), approved.referenceMap().enHash(),
                outputRuHash, outputEnHash, activationCount, deactivationCount);
    }

    private static ReleaseResult noApprovedSnapshotResult() {
        return ReleaseResult.blocked("No approved snapshot exists to release.");
    }

    private static ReleaseResult alreadyReleasedResult() {
        return ReleaseResult.blocked("A release already exists at this output root; replacing it is not yet supported.");
    }

    private record ResolvedRelease(String ruBody, String enBody, ReleaseProvenance provenance) {
    }

}
