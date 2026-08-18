package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.Occurrence;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovedTargetRegistryTest {

    @Test
    void findReturnsRoutesForACurrentlyApprovedTarget() {
        NullApprovedSnapshotWorkspace approvedSnapshotWorkspace = new NullApprovedSnapshotWorkspace();
        PublicationIdentity targetIdentity = PublicationIdentity.of("blog", "note", "target");
        ReferenceMap targetReferenceMap = ReferenceMap.of(targetIdentity, "vault-source-id-target",
                ContentHash.sha256Hex("Target RU"), ContentHash.sha256Hex("Target EN"),
                "ru-fields-hash", "en-fields-hash", "structured-hash", List.of());
        approvedSnapshotWorkspace.install(targetIdentity, CandidateSnapshot.of(
                "Target RU", "Target EN", List.of(), List.of(), "", targetReferenceMap));
        Occurrence occurrence = new Occurrence("ref-0001", 0, "vault-source-id-target", "Label RU", "Label EN");

        ApprovedTargetRegistry registry =
                ApprovedTargetRegistry.forOccurrences(List.of(occurrence), approvedSnapshotWorkspace);

        Optional<ApprovedTargetRegistry.Target> found = registry.find("vault-source-id-target");
        assertTrue(found.isPresent());
        assertEquals("/ru/notes/target/", found.get().ruRoute());
        assertEquals("/en/notes/target/", found.get().enRoute());
    }

    @Test
    void findIsAbsentWhenTheTargetHasNoApprovedSnapshot() {
        ApprovedTargetRegistry registry = ApprovedTargetRegistry.forOccurrences(
                List.of(new Occurrence("ref-0001", 0, "vault-source-id-missing", "Label RU", "Label EN")),
                new NullApprovedSnapshotWorkspace());

        assertEquals(Optional.empty(), registry.find("vault-source-id-missing"));
    }
}
