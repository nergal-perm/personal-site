package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.List;

public record LegacyWorkspaceInventory(
        List<PublicationIdentity> approvedPairs,
        List<PublicationIdentity> candidatePairs,
        List<String> ambiguities,
        List<String> blockers,
        String inventorySha256) {

    public LegacyWorkspaceInventory {
        approvedPairs = List.copyOf(approvedPairs);
        candidatePairs = List.copyOf(candidatePairs);
        ambiguities = List.copyOf(ambiguities);
        blockers = List.copyOf(blockers);
    }
}
