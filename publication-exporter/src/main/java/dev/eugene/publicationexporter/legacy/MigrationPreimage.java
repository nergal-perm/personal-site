package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import dev.eugene.publicationexporter.candidate.CandidateAsset;
import java.util.List;

public record MigrationPreimage(MigrationGeneration generation,
        Map<PublicationIdentity, CandidateSnapshot> candidateSnapshots,
        Map<PublicationIdentity, CandidateSnapshot> approvedSnapshots,
        Map<PublicationIdentity, List<CandidateAsset>> candidateAssets) {

    public MigrationPreimage(MigrationGeneration generation,
            Map<PublicationIdentity, CandidateSnapshot> candidateSnapshots,
            Map<PublicationIdentity, CandidateSnapshot> approvedSnapshots) {
        this(generation, candidateSnapshots, approvedSnapshots, Map.of());
    }

    public MigrationPreimage {
        MigrationGeneration sourceGeneration = Objects.requireNonNull(generation, "generation");
        candidateSnapshots = immutableSnapshots(candidateSnapshots, "candidateSnapshots");
        approvedSnapshots = immutableSnapshots(approvedSnapshots, "approvedSnapshots");
        candidateAssets = immutableAssets(candidateAssets);
        requireCompleteCoverage(sourceGeneration, candidateSnapshots, approvedSnapshots);
        requireCandidateAssetOwners(candidateSnapshots, candidateAssets);
    }

    private static Map<PublicationIdentity, List<CandidateAsset>> immutableAssets(
            Map<PublicationIdentity, List<CandidateAsset>> assets) {
        Objects.requireNonNull(assets, "candidateAssets");
        assets.forEach((identity, values) -> {
            Objects.requireNonNull(identity, "candidateAssets identity");
            Objects.requireNonNull(values, "candidateAssets values");
            values.forEach(asset -> Objects.requireNonNull(asset, "candidate asset"));
        });
        return assets.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    private static void requireCandidateAssetOwners(
            Map<PublicationIdentity, CandidateSnapshot> candidates,
            Map<PublicationIdentity, List<CandidateAsset>> assets) {
        if (!candidates.keySet().containsAll(assets.keySet())) {
            throw new IllegalArgumentException(
                    "candidateAssets identities must each have a candidateSnapshots entry");
        }
    }

    public boolean belongsTo(MigrationGeneration candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return generation.inventorySha256().equals(candidate.inventorySha256())
                && generation.identities().equals(candidate.identities());
    }

    private static void requireCompleteCoverage(MigrationGeneration sourceGeneration,
            Map<PublicationIdentity, CandidateSnapshot> candidates,
            Map<PublicationIdentity, CandidateSnapshot> approved) {
        Set<PublicationIdentity> covered = new java.util.HashSet<>(candidates.keySet());
        covered.addAll(approved.keySet());
        if (!covered.equals(Set.copyOf(sourceGeneration.identities()))) {
            throw new IllegalArgumentException(
                    "candidateSnapshots and approvedSnapshots union must cover every generation identity exactly");
        }
    }

    private static Map<PublicationIdentity, CandidateSnapshot> immutableSnapshots(
            Map<PublicationIdentity, CandidateSnapshot> snapshots, String name) {
        Objects.requireNonNull(snapshots, name);
        snapshots.forEach((identity, snapshot) -> {
            Objects.requireNonNull(identity, name + " identity");
            Objects.requireNonNull(snapshot, name + " snapshot");
            if (!snapshot.referenceMap().identity().equals(identity)) {
                throw new IllegalArgumentException(name + " snapshot identity does not match its map key");
            }
            MigrationSnapshotIntegrity.requireValid(identity, snapshot, name);
        });
        return Map.copyOf(snapshots);
    }
}
