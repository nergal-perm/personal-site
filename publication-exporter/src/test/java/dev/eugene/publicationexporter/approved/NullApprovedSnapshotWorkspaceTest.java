package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullApprovedSnapshotWorkspaceTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void findIsAbsentBeforeAnyInstall() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }

    @Test
    void installThenFindReturnsPathsEndingInRuMdAndEnMd() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);
        Optional<dev.eugene.publicationexporter.candidate.CandidatePaths> found = workspace.find(IDENTITY);

        assertTrue(found.isPresent());
        assertEquals("ru.md", found.get().ruPath().getFileName().toString());
        assertEquals("en.md", found.get().enPath().getFileName().toString());
    }

    @Test
    void aSecondInstallForTheSameIdentityThrows() {
        NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");
        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        assertThrows(ApprovedSnapshotAlreadyExistsException.class,
                () -> workspace.install(IDENTITY, "RU body 2", "EN body 2", referenceMap));
    }

    @Test
    void interfaceFactoryReturnsAFreshEmptyWorkspace() {
        ApprovedSnapshotWorkspace workspace = ApprovedSnapshotWorkspace.createNull();

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }
}
