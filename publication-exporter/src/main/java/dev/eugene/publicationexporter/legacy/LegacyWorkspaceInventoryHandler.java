package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class LegacyWorkspaceInventoryHandler {

    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final CandidateWorkspace candidateWorkspace;

    public LegacyWorkspaceInventoryHandler(
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, CandidateWorkspace candidateWorkspace) {
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
    }

    public LegacyWorkspaceInventory inspect() {
        List<PublicationIdentity> approvedPairs = approvedSnapshotWorkspace.allIdentities();
        List<PublicationIdentity> candidatePairs = candidateWorkspace.allIdentities();
        List<String> ambiguities = ambiguitiesAcross(approvedPairs, candidatePairs);
        List<String> blockers = blockersAcross(approvedPairs, candidatePairs);
        String inventorySha256 = fingerprint(approvedPairs, candidatePairs, ambiguities, blockers);
        return new LegacyWorkspaceInventory(approvedPairs, candidatePairs, ambiguities, blockers, inventorySha256);
    }

    private List<String> ambiguitiesAcross(
            List<PublicationIdentity> approvedPairs, List<PublicationIdentity> candidatePairs) {
        List<String> ambiguities = new ArrayList<>();
        for (PublicationIdentity identity : approvedPairs) {
            if (!candidatePairs.contains(identity)) {
                continue;
            }
            Optional<String> approvedSourceId = sourceIdOfApproved(identity);
            Optional<String> candidateSourceId = sourceIdOfCandidate(identity);
            if (!approvedSourceId.equals(candidateSourceId)) {
                ambiguities.add(identity + ": approved sourceId " + approvedSourceId
                        + " does not match candidate sourceId " + candidateSourceId);
            }
        }
        return ambiguities;
    }

    private List<String> blockersAcross(
            List<PublicationIdentity> approvedPairs, List<PublicationIdentity> candidatePairs) {
        List<String> blockers = new ArrayList<>();
        for (PublicationIdentity identity : approvedPairs) {
            if (sourceIdOfApproved(identity).isEmpty()) {
                blockers.add(identity + ": approved snapshot has no recorded source ID");
            }
        }
        for (PublicationIdentity identity : candidatePairs) {
            if (sourceIdOfCandidate(identity).isEmpty()) {
                blockers.add(identity + ": candidate snapshot has no recorded source ID");
            }
        }
        return blockers;
    }

    private Optional<String> sourceIdOfApproved(PublicationIdentity identity) {
        return approvedSnapshotWorkspace.read(identity).flatMap(snapshot -> snapshot.referenceMap().sourceId());
    }

    private Optional<String> sourceIdOfCandidate(PublicationIdentity identity) {
        return candidateWorkspace.read(identity).flatMap(snapshot -> snapshot.referenceMap().sourceId());
    }

    private static String fingerprint(
            List<PublicationIdentity> approvedPairs, List<PublicationIdentity> candidatePairs,
            List<String> ambiguities, List<String> blockers) {
        StringBuilder canonical = new StringBuilder();
        approvedPairs.forEach(identity -> canonical.append("approved:").append(identity).append('\n'));
        candidatePairs.forEach(identity -> canonical.append("candidate:").append(identity).append('\n'));
        ambiguities.forEach(entry -> canonical.append("ambiguity:").append(entry).append('\n'));
        blockers.forEach(entry -> canonical.append("blocker:").append(entry).append('\n'));
        return ContentHash.sha256Hex(canonical.toString());
    }
}
