package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullApprovedSnapshotWorkspaceTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final PublicationIdentity DIFFERENT_IDENTITY =
            PublicationIdentity.of("blog", "essay", "different-essay");

    @Test
    void findIsAbsentBeforeAnyInstall() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }

    @Test
    void installThenFindReturnsPathsEndingInRuMdAndEnMd() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "en-description-hash");

        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);
        Optional<CandidatePaths> found = workspace.find(IDENTITY);

        assertTrue(found.isPresent());
        assertEquals("/approved/blog/my-essay/approved/ru.md", found.get().ruPath().toString());
        assertEquals("/approved/blog/my-essay/approved/en.md", found.get().enPath().toString());
        assertEquals("ru.md", found.get().ruPath().getFileName().toString());
        assertEquals("en.md", found.get().enPath().getFileName().toString());
    }

    @Test
    void findIsAbsentForDifferentIdentityAfterInstallingOne() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "en-description-hash");

        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        assertEquals(Optional.empty(), workspace.find(DIFFERENT_IDENTITY));
    }

    @Test
    void secondInstallReplacesThePriorSnapshot() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        workspace.install(IDENTITY, "Old RU", "Old EN", "Old RU title", "Old EN title",
                "Old RU description", "Old EN description", referenceMap("old"));

        workspace.install(IDENTITY, "New RU", "New EN", "New RU title", "New EN title",
                "New RU description", "New EN description", referenceMap("new"));

        Optional<dev.eugene.publicationexporter.candidate.CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("New RU", read.get().ruBody());
        assertEquals("New EN", read.get().enBody());
    }

    private static ReferenceMap referenceMap(String hash) {
        return ReferenceMap.empty(IDENTITY, hash + "-ru", hash + "-en", hash + "-ru-title", hash + "-en-title", hash + "-en-description");
    }

    @Test
    void interfaceFactoryReturnsAFreshEmptyWorkspace() {
        ApprovedSnapshotWorkspace workspace = ApprovedSnapshotWorkspace.createNull();

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }

    @Test
    void readIsAbsentBeforeAnyInstall() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void readReturnsTheInstalledBodiesAndReferenceMap() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "en-description-hash");
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Optional<dev.eugene.publicationexporter.candidate.CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU body", read.get().ruBody());
        assertEquals("EN body", read.get().enBody());
        assertEquals("RU title", PublicField.value(read.get().ruFields(), "title").orElseThrow());
        assertEquals("EN title", PublicField.value(read.get().enFields(), "title").orElseThrow());
        assertEquals("RU description.", PublicField.value(read.get().ruFields(), "description").orElseThrow());
        assertEquals("EN description.", PublicField.value(read.get().enFields(), "description").orElseThrow());
        assertEquals(referenceMap, read.get().referenceMap());
    }

    @Test
    void readIsAbsentForADifferentIdentity() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "en-description-hash"));

        assertEquals(Optional.empty(), workspace.read(DIFFERENT_IDENTITY));
    }

    @Test
    void readReturnsTheInstalledTitleAndDescription() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "en-description-hash");
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Optional<dev.eugene.publicationexporter.candidate.CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU title", PublicField.value(read.get().ruFields(), "title").orElseThrow());
        assertEquals("EN title", PublicField.value(read.get().enFields(), "title").orElseThrow());
    }
}
