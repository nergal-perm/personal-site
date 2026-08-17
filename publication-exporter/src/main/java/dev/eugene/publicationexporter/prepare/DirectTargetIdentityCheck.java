package dev.eugene.publicationexporter.prepare;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

final class DirectTargetIdentityCheck {

    private DirectTargetIdentityCheck() {
    }

    static DirectTargetIdentityOutcome verify(
            String sourceStem, String sourceId, Set<String> targetStems, VaultSourceIdentityIndex index) {
        Set<String> seenSourceIds = new HashSet<>();
        seenSourceIds.add(sourceId);
        for (String targetStem : targetStems) {
            if (targetStem.equals(sourceStem)) {
                continue;
            }
            Optional<DirectTargetIdentityOutcome> blocked = checkTarget(targetStem, index, seenSourceIds);
            if (blocked.isPresent()) {
                return blocked.get();
            }
        }
        return DirectTargetIdentityOutcome.admitted();
    }

    private static Optional<DirectTargetIdentityOutcome> checkTarget(
            String targetStem, VaultSourceIdentityIndex index, Set<String> seenSourceIds) {
        Optional<TargetIdentity> identity = index.identityFor(targetStem);
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> targetSourceId = identity.get().sourceId();
        if (targetSourceId.isEmpty()) {
            return Optional.of(DirectTargetIdentityOutcome.blocked(
                    "Direct target \"" + targetStem + "\" has no source ID."));
        }
        if (!seenSourceIds.add(targetSourceId.get())) {
            return Optional.of(DirectTargetIdentityOutcome.blocked(
                    "Direct target \"" + targetStem + "\" shares a source ID with another note."));
        }
        return Optional.empty();
    }
}
