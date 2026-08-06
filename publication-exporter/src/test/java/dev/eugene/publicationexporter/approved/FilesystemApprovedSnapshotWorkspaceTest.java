package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.ReferenceMapCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemApprovedSnapshotWorkspaceTest {

    @TempDir
    Path reviewRoot;

    @TempDir
    Path outsideRoot;

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void installWritesAllSnapshotFilesAtTheirFinalPath() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        assertEquals("RU body", Files.readString(approvedDir.resolve("ru.md")));
        assertEquals("EN body", Files.readString(approvedDir.resolve("en.md")));
        assertEquals("RU title", Files.readString(approvedDir.resolve("ru.title")));
        assertEquals("EN title", Files.readString(approvedDir.resolve("en.title")));
        assertEquals("RU description.", Files.readString(approvedDir.resolve("ru.description")));
        assertEquals("EN description.", Files.readString(approvedDir.resolve("en.description")));
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
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        Optional<CandidatePaths> found = workspace.find(IDENTITY);

        assertTrue(found.isPresent());
        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        assertEquals(approvedDir.resolve("ru.md").toRealPath(), found.get().ruPath().toRealPath());
        assertTrue(found.get().ruPath().isAbsolute());
    }

    @Test
    void readIsAbsentBeforeInstall() {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void readReturnsTheInstalledBodiesAndReferenceMap() {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Optional<dev.eugene.publicationexporter.candidate.CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU body", read.get().ruBody());
        assertEquals("EN body", read.get().enBody());
        assertEquals("RU title", read.get().ruTitle());
        assertEquals("EN title", read.get().enTitle());
        assertEquals("RU description.", read.get().ruDescription());
        assertEquals("EN description.", read.get().enDescription());
        assertEquals(referenceMap, read.get().referenceMap());
    }

    @Test
    void readRejectsSymlinkedMemberFileEscapingReviewRoot() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));
        Path enPath = reviewRoot.resolve("blog/my-essay/approved/en.md");
        Path outsideEnPath = outsideRoot.resolve("outside-en.md");
        Files.writeString(outsideEnPath, "outside EN body");
        Files.delete(enPath);
        Files.createSymbolicLink(enPath, outsideEnPath);

        assertThrows(ApprovedSnapshotWorkspaceConfinementException.class, () -> workspace.read(IDENTITY));
    }

    @Test
    void readIsAbsentForAMismatchingReferenceMapIdentity() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        Path referencesPath = reviewRoot.resolve("blog/my-essay/approved/references.json");
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");
        ReferenceMap mismatching = ReferenceMap.empty(otherIdentity, "ru-hash", "en-hash");
        Files.writeString(referencesPath, ReferenceMapCodec.write(mismatching), StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void aSecondInstallForTheSameIdentityThrowsAndLeavesTheFirstSnapshotIntact() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        String referencesBeforeRejection = Files.readString(approvedDir.resolve("references.json"));

        assertThrows(ApprovedSnapshotAlreadyExistsException.class,
                () -> workspace.install(IDENTITY, "RU body 2", "EN body 2", "RU title 2", "EN title 2",
                        "RU description 2.", "EN description 2.", referenceMap));

        assertEquals("RU body", Files.readString(approvedDir.resolve("ru.md")));
        assertEquals("EN body", Files.readString(approvedDir.resolve("en.md")));
        assertEquals(referencesBeforeRejection, Files.readString(approvedDir.resolve("references.json")));
    }

    @Test
    void noStagingDirectoryIsLeftBehindAfterASuccessfulInstall() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        workspace.install(IDENTITY, "RU", "EN", "RU title", "EN title", "RU description.",
                "EN description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

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
                () -> workspace.install(escapingIdentity, "RU", "EN", "RU title", "EN title",
                        "RU description.", "EN description.",
                        ReferenceMap.empty(escapingIdentity, "ru-hash", "en-hash")));

        assertTrue(Files.notExists(freshRoot));
    }

    @Test
    void installRejectsSymlinkedChildEscapingReviewRoot() throws Exception {
        Files.createSymbolicLink(reviewRoot.resolve("blog"), outsideRoot);
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        assertThrows(IllegalStateException.class,
                () -> workspace.install(IDENTITY, "RU", "EN", "RU title", "EN title",
                        "RU description.", "EN description.",
                        ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash")));

        assertTrue(Files.notExists(outsideRoot.resolve("my-essay/approved")));
        try (var entries = Files.list(reviewRoot)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith("approved-staging-")));
        }
    }
}
