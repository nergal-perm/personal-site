package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemApprovedSnapshotWorkspaceTest {

    @TempDir
    Path reviewRoot;

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void installWritesAllThreeFilesAtTheirFinalPath() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        assertEquals("RU body", Files.readString(approvedDir.resolve("ru.md")));
        assertEquals("EN body", Files.readString(approvedDir.resolve("en.md")));
        assertTrue(Files.readString(approvedDir.resolve("references.json")).contains("\"ruHash\":\"ru-hash\""));
    }

    @Test
    void findIsAbsentBeforeInstall() {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }

    @Test
    void findReturnsAbsolutePathsToTheInstalledFiles() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        workspace.install(IDENTITY, "RU body", "EN body", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        Optional<CandidatePaths> found = workspace.find(IDENTITY);

        assertTrue(found.isPresent());
        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        assertEquals(approvedDir.resolve("ru.md").toRealPath(), found.get().ruPath().toRealPath());
        assertTrue(found.get().ruPath().isAbsolute());
    }

    @Test
    void aSecondInstallForTheSameIdentityThrowsAndLeavesTheFirstSnapshotIntact() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");
        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        assertThrows(ApprovedSnapshotAlreadyExistsException.class,
                () -> workspace.install(IDENTITY, "RU body 2", "EN body 2", referenceMap));

        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        assertEquals("RU body", Files.readString(approvedDir.resolve("ru.md")));
    }

    @Test
    void noStagingDirectoryIsLeftBehindAfterASuccessfulInstall() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        workspace.install(IDENTITY, "RU", "EN", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        try (var entries = Files.list(reviewRoot)) {
            long stagingLeftovers = entries
                    .filter(path -> path.getFileName().toString().startsWith("approved-staging-"))
                    .count();
            assertEquals(0, stagingLeftovers);
        }
    }

    @Test
    void installRejectsCollectionParentSegmentBeforeAnyWrite() {
        Path freshRoot = reviewRoot.resolve("fresh-review-root");
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(freshRoot);
        PublicationIdentity escapingIdentity = PublicationIdentity.of("..", "essay", "escaped-collection");

        assertThrows(IllegalStateException.class,
                () -> workspace.install(escapingIdentity, "RU", "EN",
                        ReferenceMap.empty(escapingIdentity, "ru-hash", "en-hash")));

        assertTrue(Files.notExists(freshRoot));
    }
}
