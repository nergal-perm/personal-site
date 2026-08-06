package dev.eugene.publicationexporter.buildfromreview;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.release.ReleaseAlreadyExistsException;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import dev.eugene.publicationexporter.release.ReleaseProvenance;

import java.io.UncheckedIOException;
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
        Optional<CandidateSnapshot> approved = approvedSnapshotWorkspace.read(identity);
        if (approved.isEmpty()) {
            return noApprovedSnapshotResult();
        }
        return releaseApprovedSnapshot(identity, approved.get());
    }

    private ReleaseResult releaseApprovedSnapshot(PublicationIdentity identity, CandidateSnapshot approved) {
        ReleaseProvenance provenance = provenanceFor(identity, approved);
        try {
            releaseOutputStore.install(identity, approved.ruBody(), approved.enBody(), provenance);
        } catch (ReleaseAlreadyExistsException raceLoser) {
            return alreadyReleasedResult();
        } catch (UncheckedIOException failure) {
            return ReleaseResult.blocked("Release installation failed.");
        }
        return ReleaseResult.released(identity, provenance);
    }

    private static ReleaseProvenance provenanceFor(PublicationIdentity identity, CandidateSnapshot approved) {
        String outputRuHash = ContentHash.sha256Hex(approved.ruBody());
        String outputEnHash = ContentHash.sha256Hex(approved.enBody());
        return ReleaseProvenance.of(identity,
                approved.referenceMap().ruHash(), approved.referenceMap().enHash(),
                outputRuHash, outputEnHash);
    }

    private static ReleaseResult noApprovedSnapshotResult() {
        return ReleaseResult.blocked("No approved snapshot exists to release.");
    }

    private static ReleaseResult alreadyReleasedResult() {
        return ReleaseResult.blocked("A release already exists at this output root; replacing it is not yet supported.");
    }
}
