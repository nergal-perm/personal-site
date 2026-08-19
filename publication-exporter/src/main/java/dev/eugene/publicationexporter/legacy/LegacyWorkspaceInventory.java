package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;

public record LegacyWorkspaceInventory(
        List<PublicationIdentity> approvedPairs,
        List<PublicationIdentity> candidatePairs,
        List<String> ambiguities,
        List<String> blockers,
        String inventorySha256) {

    public LegacyWorkspaceInventory {
        approvedPairs = List.copyOf(Objects.requireNonNull(approvedPairs, "approvedPairs"));
        candidatePairs = List.copyOf(Objects.requireNonNull(candidatePairs, "candidatePairs"));
        ambiguities = List.copyOf(ambiguities);
        blockers = List.copyOf(blockers);
        rejectDuplicates(approvedPairs, "approvedPairs");
        rejectDuplicates(candidatePairs, "candidatePairs");
    }

    private static void rejectDuplicates(List<PublicationIdentity> identities, String name) {
        if (identities.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException(name + " must not contain null");
        }
        if (new HashSet<>(identities).size() != identities.size()) {
            throw new IllegalArgumentException(name + " must not contain duplicate identities");
        }
    }
}
