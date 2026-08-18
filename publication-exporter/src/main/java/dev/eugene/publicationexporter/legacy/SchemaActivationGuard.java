package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;

import java.util.Objects;

public final class SchemaActivationGuard {

    private static final String BLOCKING_REASON =
            "Workspace has approved or candidate content with no valid semantic schema activation marker. "
                    + "Run the read-only migration inventory before retrying.";

    private SchemaActivationGuard() {
    }

    public static SchemaActivationCheck check(
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, ActivationMarkerStore activationMarkerStore) {
        Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        Objects.requireNonNull(activationMarkerStore, "activationMarkerStore");
        if (hasValidMarker(activationMarkerStore)) {
            return SchemaActivationCheck.current();
        }
        return approvedSnapshotWorkspace.allIdentities().isEmpty()
                ? SchemaActivationCheck.current()
                : SchemaActivationCheck.legacy(BLOCKING_REASON);
    }

    public static SchemaActivationCheck check(
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, CandidateWorkspace candidateWorkspace,
            ActivationMarkerStore activationMarkerStore) {
        Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        Objects.requireNonNull(activationMarkerStore, "activationMarkerStore");
        if (hasValidMarker(activationMarkerStore)) {
            return SchemaActivationCheck.current();
        }
        boolean hasLegacyContent = !approvedSnapshotWorkspace.allIdentities().isEmpty()
                || !candidateWorkspace.allIdentities().isEmpty();
        return hasLegacyContent ? SchemaActivationCheck.legacy(BLOCKING_REASON) : SchemaActivationCheck.current();
    }

    private static boolean hasValidMarker(ActivationMarkerStore activationMarkerStore) {
        return activationMarkerStore.read().filter(ActivationMarker::isValid).isPresent();
    }
}
