package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.reference.Occurrence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OccurrenceAssignmentTest {

    @Test
    void reusesPriorIdWhenTargetSourceIdMatchesAtTheSameIndex() {
        LinkOccurrence current = new LinkOccurrence("grandpa-shvedov", "дед Шведов", Optional.empty(), 0, 10);
        Occurrence previous = new Occurrence("occ-existing", 0, "src-grandpa", "дед Шведов", "Grandpa Shvedov");

        List<OccurrenceAssignment.AssignedOccurrence> assigned = OccurrenceAssignment.assign(
                List.of(current), Map.of("grandpa-shvedov", "src-grandpa"), List.of(previous));

        assertEquals(1, assigned.size());
        assertEquals("occ-existing", assigned.get(0).id());
        assertEquals(0, assigned.get(0).order());
        assertEquals("src-grandpa", assigned.get(0).targetSourceId());
    }

    @Test
    void assignsAFreshIdWhenNoPreviousOccurrenceExists() {
        LinkOccurrence current = new LinkOccurrence("new-target", "New Target", Optional.empty(), 0, 10);

        List<OccurrenceAssignment.AssignedOccurrence> assigned = OccurrenceAssignment.assign(
                List.of(current), Map.of("new-target", "src-new"), List.of());
        List<OccurrenceAssignment.AssignedOccurrence> assignedAgain = OccurrenceAssignment.assign(
                List.of(current), Map.of("new-target", "src-new"), List.of());

        assertEquals(1, assigned.size());
        assertFalse(assigned.get(0).id().isBlank());
        assertNotEquals(assigned.get(0).id(), assignedAgain.get(0).id());
        assertEquals("src-new", assigned.get(0).targetSourceId());
    }

    @Test
    void returnsAnImmutableAssignmentList() {
        LinkOccurrence current = new LinkOccurrence("target", "Target", Optional.empty(), 0, 10);

        List<OccurrenceAssignment.AssignedOccurrence> assigned = OccurrenceAssignment.assign(
                List.of(current), Map.of("target", "src-target"), List.of());

        assertThrows(UnsupportedOperationException.class, () -> assigned.add(null));
    }

    @Test
    void assignsAFreshIdWhenTargetSourceIdDiffersAtTheSameIndex() {
        LinkOccurrence current = new LinkOccurrence("changed-target", "Changed", Optional.empty(), 0, 10);
        Occurrence previous = new Occurrence("occ-old", 0, "src-old", "Old", "Old");

        List<OccurrenceAssignment.AssignedOccurrence> assigned = OccurrenceAssignment.assign(
                List.of(current), Map.of("changed-target", "src-new"), List.of(previous));

        assertNotEquals("occ-old", assigned.get(0).id());
        assertEquals("src-new", assigned.get(0).targetSourceId());
    }

    @Test
    void breaksCorrespondenceFromTheFirstMismatchOnward() {
        LinkOccurrence firstCurrent = new LinkOccurrence("a", "A", Optional.empty(), 0, 1);
        LinkOccurrence secondCurrent = new LinkOccurrence("b", "B", Optional.empty(), 2, 3);
        Occurrence firstPrevious = new Occurrence("occ-a", 0, "src-a", "A", "A");
        Occurrence secondPrevious = new Occurrence("occ-b", 1, "src-b", "B", "B");

        List<OccurrenceAssignment.AssignedOccurrence> assigned = OccurrenceAssignment.assign(
                List.of(firstCurrent, secondCurrent),
                Map.of("a", "src-x", "b", "src-b"),
                List.of(firstPrevious, secondPrevious));

        assertNotEquals("occ-a", assigned.get(0).id());
        assertNotEquals("occ-b", assigned.get(1).id());
    }
}
