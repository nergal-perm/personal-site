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
        boolean priorOccurrenceCorrespondenceIntact = true;
        for (int index = 0; index < ruOccurrences.size(); index++) {
            LinkOccurrence current = ruOccurrences.get(index);
            String targetSourceId = targetSourceIdsByStem.get(current.targetStem());
            boolean correspondsToPriorOccurrence = correspondsToPriorOccurrence(
                    index, targetSourceId, previousOccurrences);
            String occurrenceId = priorOccurrenceCorrespondenceIntact && correspondsToPriorOccurrence
                    ? previousOccurrences.get(index).id()
                    : freshOccurrenceId();
            assigned.add(new AssignedOccurrence(
                    occurrenceId, index, targetSourceId, current.label()));
            priorOccurrenceCorrespondenceIntact =
                    priorOccurrenceCorrespondenceIntact && correspondsToPriorOccurrence;
        }
        return List.copyOf(assigned);
    }

    private static boolean correspondsToPriorOccurrence(
            int index, String targetSourceId, List<Occurrence> previousOccurrences) {
        return index < previousOccurrences.size()
                && previousOccurrences.get(index).targetSourceId().equals(targetSourceId);
    }

    private static String freshOccurrenceId() {
        return UUID.randomUUID().toString();
    }

    record AssignedOccurrence(String id, int order, String targetSourceId, String ruLabel) {

        AssignedOccurrence {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(targetSourceId, "targetSourceId");
            Objects.requireNonNull(ruLabel, "ruLabel");
        }
    }
}
