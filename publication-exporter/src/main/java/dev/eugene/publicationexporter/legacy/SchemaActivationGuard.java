package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;

import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class SchemaActivationGuard {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final String BLOCKING_REASON =
            "Workspace has approved or candidate content with no valid semantic schema activation marker. "
                    + "Run the read-only migration inventory before retrying.";
    private static final String INCOMPLETE_MIGRATION_REASON =
            "Migration is incomplete or inconsistent; explicitly roll forward or roll back before retrying.";

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

    public static SchemaActivationCheck check(
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, CandidateWorkspace candidateWorkspace,
            ActivationMarkerStore activationMarkerStore, MigrationJournalStore migrationJournalStore,
            MigrationCatalogStore migrationCatalogStore) {
        Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        Objects.requireNonNull(activationMarkerStore, "activationMarkerStore");
        Objects.requireNonNull(migrationJournalStore, "migrationJournalStore");
        Objects.requireNonNull(migrationCatalogStore, "migrationCatalogStore");

        try {
            return classify(approvedSnapshotWorkspace, candidateWorkspace,
                    artifacts(activationMarkerStore, migrationJournalStore, migrationCatalogStore));
        } catch (RuntimeException failure) {
            return SchemaActivationCheck.legacy(INCOMPLETE_MIGRATION_REASON);
        }
    }

    private static ActivationArtifacts artifacts(
            ActivationMarkerStore markers, MigrationJournalStore journals, MigrationCatalogStore catalogs) {
        return new ActivationArtifacts(markers.inspect(), journals.read(), journals.preimage(), catalogs.read());
    }

    private static SchemaActivationCheck classify(
            ApprovedSnapshotWorkspace approved, CandidateWorkspace candidate, ActivationArtifacts artifacts) {
        if (artifacts.allAbsent()) {
            return workspaceWithoutMigrationArtifacts(approved, candidate);
        }
        if (!artifacts.complete()) {
            return SchemaActivationCheck.legacy(INCOMPLETE_MIGRATION_REASON);
        }
        return completeGeneration(approved, artifacts.marker().marker().orElseThrow(),
                artifacts.journal().orElseThrow(), artifacts.manifest().orElseThrow(),
                artifacts.catalog().orElseThrow());
    }

    private static SchemaActivationCheck completeGeneration(
            ApprovedSnapshotWorkspace approved, ActivationMarker marker,
            MigrationGeneration journal, MigrationPreimage manifest, MigrationGeneration catalog) {
        if (!marker.isValid() || !journal.isSealed() || !catalog.isSealed()
                || !sameGeneration(journal, catalog)
                || !manifest.belongsTo(journal)
                || marker.schemaVersion() != 1
                || !marker.inventorySha256().equals(journal.inventorySha256())
                || !completeApprovedSnapshots(approved, manifest)) {
            return SchemaActivationCheck.legacy(INCOMPLETE_MIGRATION_REASON);
        }
        return SchemaActivationCheck.current();
    }

    private static boolean sameGeneration(MigrationGeneration first, MigrationGeneration second) {
        return first.inventorySha256().equals(second.inventorySha256())
                && first.identities().equals(second.identities())
                && first.completedSteps() == second.completedSteps();
    }

    private static boolean completeApprovedSnapshots(
            ApprovedSnapshotWorkspace approved, MigrationPreimage manifest) {
        List<PublicationIdentity> identities;
        try {
            identities = approved.allIdentities();
        } catch (RuntimeException failure) {
            return false;
        }
        Set<PublicationIdentity> current = Set.copyOf(identities);
        if (current.size() != identities.size()
                || !current.containsAll(manifest.approvedSnapshots().keySet())) {
            return false;
        }
        for (var identity : identities) {
            try {
                Optional<CandidateSnapshot> snapshot = approved.read(identity);
                if (snapshot.isEmpty() || !admissibleCurrentApproval(identity, snapshot.orElseThrow(), manifest)) {
                    return false;
                }
            } catch (RuntimeException failure) {
                return false;
            }
        }
        return true;
    }

    private static boolean admissibleCurrentApproval(
            PublicationIdentity identity, CandidateSnapshot current, MigrationPreimage manifest) {
        CandidateSnapshot migrated = manifest.approvedSnapshots().get(identity);
        return migrated != null && migrated.equals(current)
                ? validTriple(identity, current)
                : currentSchemaSnapshot(identity, current);
    }

    private static boolean validTriple(
            PublicationIdentity identity, CandidateSnapshot snapshot) {
        return snapshot.referenceMap().sourceId().isPresent()
                && MigrationSnapshotIntegrity.valid(identity, snapshot);
    }

    private static SchemaActivationCheck workspaceWithoutMigrationArtifacts(
            ApprovedSnapshotWorkspace approved, CandidateWorkspace candidate) {
        try {
            List<PublicationIdentity> approvedIdentities = approved.allIdentities();
            List<PublicationIdentity> candidateIdentities = candidate.allIdentities();
            if (approvedIdentities.isEmpty() && candidateIdentities.isEmpty()) {
                return SchemaActivationCheck.current();
            }
            boolean current = currentSchemaSnapshots(approvedIdentities, approved::read)
                    && currentSchemaSnapshots(candidateIdentities, candidate::read);
            return current ? SchemaActivationCheck.current() : SchemaActivationCheck.legacy(BLOCKING_REASON);
        } catch (RuntimeException failure) {
            return SchemaActivationCheck.legacy(BLOCKING_REASON);
        }
    }

    private static boolean currentSchemaSnapshots(
            List<PublicationIdentity> identities,
            Function<PublicationIdentity, Optional<CandidateSnapshot>> snapshotFor) {
        if (Set.copyOf(identities).size() != identities.size()) {
            return false;
        }
        return identities.stream().allMatch(identity -> snapshotFor.apply(identity)
                .filter(snapshot -> currentSchemaSnapshot(identity, snapshot))
                .isPresent());
    }

    private static boolean currentSchemaSnapshot(
            PublicationIdentity identity, CandidateSnapshot snapshot) {
        return validTriple(identity, snapshot)
                && SHA256.matcher(snapshot.referenceMap().sourceBodyHash()).matches();
    }

    private static boolean hasValidMarker(ActivationMarkerStore activationMarkerStore) {
        return activationMarkerStore.read().filter(ActivationMarker::isValid).isPresent();
    }

    private record ActivationArtifacts(
            ActivationMarkerStore.Inspection marker,
            Optional<MigrationGeneration> journal,
            Optional<MigrationPreimage> manifest,
            Optional<MigrationGeneration> catalog) {

        private ActivationArtifacts {
            Objects.requireNonNull(marker, "marker");
            Objects.requireNonNull(journal, "journal");
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(catalog, "catalog");
        }

        private boolean allAbsent() {
            return marker.status() == ActivationMarkerStore.Status.ABSENT
                    && journal.isEmpty() && manifest.isEmpty() && catalog.isEmpty();
        }

        private boolean complete() {
            return marker.status() == ActivationMarkerStore.Status.PARSED
                    && journal.isPresent() && manifest.isPresent() && catalog.isPresent();
        }
    }
}
