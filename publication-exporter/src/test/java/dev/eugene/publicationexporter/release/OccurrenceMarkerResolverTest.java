package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.Occurrence;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OccurrenceMarkerResolverTest {

    @Test
    void activatesAMarkerWhoseTargetIsCurrentlyApproved() {
        List<Occurrence> occurrences = List.of(
                new Occurrence("ref-0001", 0, "vault-source-id-target", "Target Label RU", "Target Label EN"));
        ApprovedTargetRegistry registry = registryResolving("vault-source-id-target");

        OccurrenceResolution resolution = OccurrenceMarkerResolver.resolve(
                "See [Target Label RU](ref:vault-source-id-target).", registry, occurrences, "ru");

        assertEquals("See [Target Label RU](/ru/notes/target/).", resolution.body());
        assertEquals(1, resolution.activatedCount());
        assertEquals(0, resolution.deactivatedCount());
    }

    @Test
    void deactivatesAMarkerWhoseTargetHasNoCurrentApprovedSnapshot() {
        List<Occurrence> occurrences = List.of(
                new Occurrence("ref-0001", 0, "vault-source-id-missing", "Target Label RU", "Target Label EN"));

        OccurrenceResolution resolution = OccurrenceMarkerResolver.resolve(
                "See [Inline](ref:vault-source-id-missing).", emptyRegistry(), occurrences, "ru");

        assertEquals("See Target Label RU.", resolution.body());
        assertEquals(0, resolution.activatedCount());
        assertEquals(1, resolution.deactivatedCount());
    }

    @Test
    void resolvesMultipleMarkersAndAddsActivationAndDeactivationCounts() {
        List<Occurrence> occurrences = List.of(
                new Occurrence("ref-0001", 0, "vault-source-id-target", "Target Label RU", "Target Label EN"),
                new Occurrence("ref-0002", 1, "vault-source-id-missing", "Missing Label RU", "Missing Label EN"));

        OccurrenceResolution resolution = OccurrenceMarkerResolver.resolve(
                "[Inline target](ref:vault-source-id-target) and [Inline missing](ref:vault-source-id-missing).",
                registryResolving("vault-source-id-target"), occurrences, "ru");

        assertEquals("[Target Label RU](/ru/notes/target/) and Missing Label RU.", resolution.body());
        assertEquals(1, resolution.activatedCount());
        assertEquals(1, resolution.deactivatedCount());
    }

    @Test
    void preservesDistinctStoredLabelsForRepeatedOccurrencesOfTheSameTarget() {
        List<Occurrence> occurrences = List.of(
                new Occurrence("ref-0001", 0, "vault-source-id-target", "Первый", "First"),
                new Occurrence("ref-0002", 1, "vault-source-id-target", "Второй", "Second"));

        OccurrenceResolution resolution = OccurrenceMarkerResolver.resolve(
                "[inline one](ref:vault-source-id-target) and [inline two](ref:vault-source-id-target)",
                registryResolving("vault-source-id-target"), occurrences, "ru");

        assertEquals("[Первый](/ru/notes/target/) and [Второй](/ru/notes/target/)", resolution.body());
    }

    @Test
    void skipsNonMarkerOccurrencesBeforeMatchingRepeatedTargetLabels() {
        List<Occurrence> occurrences = List.of(
                new Occurrence("ref-0001", 0, "private-target", "Приватный", "Private"),
                new Occurrence("ref-0002", 1, "vault-source-id-target", "Первый", "First"),
                new Occurrence("ref-0003", 2, "vault-source-id-target", "Второй", "Second"));

        OccurrenceResolution resolution = OccurrenceMarkerResolver.resolve(
                "[inline one](ref:vault-source-id-target) and [inline two](ref:vault-source-id-target)",
                registryResolving("vault-source-id-target"), occurrences, "ru");

        assertEquals("[Первый](/ru/notes/target/) and [Второй](/ru/notes/target/)", resolution.body());
    }

    @Test
    void markerWithEmptyOccurrencesFallsBackToInlineLabel() {
        OccurrenceResolution resolution = OccurrenceMarkerResolver.resolve(
                "See [Inline](ref:vault-source-id-missing).", emptyRegistry(), List.of(), "ru");

        assertEquals("See Inline.", resolution.body());
        assertEquals(0, resolution.activatedCount());
        assertEquals(1, resolution.deactivatedCount());
    }

    @Test
    void nonRussianLanguageUsesEnglishLabelsAndRoutes() {
        List<Occurrence> occurrences = List.of(
                new Occurrence("ref-0001", 0, "vault-source-id-target", "Target Label RU", "Target Label EN"));

        OccurrenceResolution resolution = OccurrenceMarkerResolver.resolve(
                "See [Inline](ref:vault-source-id-target).", registryResolving("vault-source-id-target"),
                occurrences, "en");

        assertEquals("See [Target Label EN](/en/notes/target/).", resolution.body());
    }

    @Test
    void aBodyWithNoMarkersIsReturnedUnchangedWithZeroCounts() {
        OccurrenceResolution resolution = OccurrenceMarkerResolver.resolve(
                "No links here.", emptyRegistry(), List.of(), "ru");

        assertEquals("No links here.", resolution.body());
        assertEquals(0, resolution.activatedCount());
        assertEquals(0, resolution.deactivatedCount());
    }

    private static ApprovedTargetRegistry registryResolving(String sourceId) {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        PublicationIdentity identity = PublicationIdentity.of("blog", "note", "target");
        workspace.install(identity, CandidateSnapshot.of(
                "Target RU", "Target EN", List.of(), List.of(), "",
                ReferenceMap.of(identity, sourceId,
                        ContentHash.sha256Hex("Target RU"), ContentHash.sha256Hex("Target EN"),
                        "ru-fields-hash", "en-fields-hash", "structured-hash", List.of())));
        return ApprovedTargetRegistry.forOccurrences(
                List.of(new Occurrence("ref-0001", 0, sourceId, "Label RU", "Label EN")), workspace);
    }

    private static ApprovedTargetRegistry emptyRegistry() {
        return ApprovedTargetRegistry.forOccurrences(List.of(), new NullApprovedSnapshotWorkspace());
    }
}
