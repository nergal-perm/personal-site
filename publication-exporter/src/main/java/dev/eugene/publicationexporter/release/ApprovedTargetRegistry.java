package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.Occurrence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ApprovedTargetRegistry {

    private final Map<String, Target> targetsBySourceId;

    private ApprovedTargetRegistry(Map<String, Target> targetsBySourceId) {
        this.targetsBySourceId = Map.copyOf(targetsBySourceId);
    }

    static ApprovedTargetRegistry forOccurrences(
            List<Occurrence> occurrences, ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
        Objects.requireNonNull(occurrences, "occurrences");
        Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        Map<String, Target> targets = new LinkedHashMap<>();
        for (Occurrence occurrence : occurrences) {
            String targetSourceId = occurrence.targetSourceId();
            if (targets.containsKey(targetSourceId)) {
                continue;
            }
            approvedSnapshotWorkspace.findBySourceId(targetSourceId)
                    .ifPresent(target -> targets.put(targetSourceId, routeFor(target)));
        }
        return new ApprovedTargetRegistry(targets);
    }

    public Optional<Target> find(String targetSourceId) {
        return Optional.ofNullable(targetsBySourceId.get(Objects.requireNonNull(targetSourceId, "targetSourceId")));
    }

    private static Target routeFor(CandidateSnapshot target) {
        String collection = target.referenceMap().identity().publicCollection();
        String contentType = target.referenceMap().identity().publicContentType();
        String publicId = target.referenceMap().identity().publicId();
        String routePrefix = PublicationKinds.installed().forIdentity(collection, contentType)
                .orElseThrow(() -> new IllegalStateException(
                        "No publication kind for " + collection + "/" + contentType))
                .routePrefix();
        return new Target("/ru/" + routePrefix + "/" + publicId + "/",
                "/en/" + routePrefix + "/" + publicId + "/");
    }

    public record Target(String ruRoute, String enRoute) { }
}
