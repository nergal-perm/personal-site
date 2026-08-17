package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.reference.Occurrence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class OccurrenceAssignment {

    private OccurrenceAssignment() {
    }

    static List<AssignedOccurrence> assign(
            List<LinkOccurrence> ruOccurrences,
            Map<String, String> targetSourceIdsByStem,
            List<Occurrence> previousOccurrences) {
        List<AssignedOccurrence> assigned = new ArrayList<>();
        boolean reuse = true;
        for (int index = 0; index < ruOccurrences.size(); index++) {
            LinkOccurrence current = ruOccurrences.get(index);
            String targetSourceId = targetSourceIdsByStem.get(current.targetStem());
            assigned.add(new AssignedOccurrence(
                    idFor(index, targetSourceId, previousOccurrences, reuse), index, targetSourceId, current.label()));
            if (reuse && idForNotReused(index, targetSourceId, previousOccurrences)) {
                reuse = false;
            }
        }
        return assigned;
    }

    private static String idFor(int index, String targetSourceId, List<Occurrence> previousOccurrences, boolean reuse) {
        if (!reuse || index >= previousOccurrences.size()) {
            return UUID.randomUUID().toString();
        }
        Occurrence previous = previousOccurrences.get(index);
        return previous.targetSourceId().equals(targetSourceId) ? previous.id() : UUID.randomUUID().toString();
    }

    private static boolean idForNotReused(int index, String targetSourceId, List<Occurrence> previousOccurrences) {
        if (index >= previousOccurrences.size()) {
            return true;
        }
        return !previousOccurrences.get(index).targetSourceId().equals(targetSourceId);
    }

    record AssignedOccurrence(String id, int order, String targetSourceId, String ruLabel) {

        AssignedOccurrence {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(targetSourceId, "targetSourceId");
            Objects.requireNonNull(ruLabel, "ruLabel");
        }
    }
}
