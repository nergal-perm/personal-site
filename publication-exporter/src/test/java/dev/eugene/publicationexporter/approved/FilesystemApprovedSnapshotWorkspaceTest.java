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
import java.nio.file.StandardCopyOption;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;
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
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash");

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
                "RU description.", "EN description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));

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
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash");
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
                "RU description.", "EN description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));
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
                "RU description.", "EN description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));

        Path referencesPath = reviewRoot.resolve("blog/my-essay/approved/references.json");
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");
        ReferenceMap mismatching = ReferenceMap.empty(otherIdentity, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash");
        Files.writeString(referencesPath, ReferenceMapCodec.write(mismatching), StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void aSecondInstallForTheSameIdentityReplacesTheFirstSnapshot() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash");
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Path approvedDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("approved");
        workspace.install(IDENTITY, "RU body 2", "EN body 2", "RU title 2", "EN title 2",
                "RU description 2.", "EN description 2.", referenceMap);

        assertEquals("RU body 2", Files.readString(approvedDir.resolve("ru.md")));
        assertEquals("EN body 2", Files.readString(approvedDir.resolve("en.md")));
        assertEquals("RU title 2", Files.readString(approvedDir.resolve("ru.title")));
        assertEquals("EN title 2", Files.readString(approvedDir.resolve("en.title")));
        assertEquals("RU description 2.", Files.readString(approvedDir.resolve("ru.description")));
        assertEquals("EN description 2.", Files.readString(approvedDir.resolve("en.description")));
    }

    @Test
    void failedNewMoveRestoresFullyReadableOldApprovedSnapshot() throws Exception {
        new FilesystemApprovedSnapshotWorkspace(reviewRoot).install(
                IDENTITY, "Old RU", "Old EN", "Old RU title", "Old EN title",
                "Old RU description", "Old EN description", referenceMap("old"));
        AtomicInteger moves = new AtomicInteger();
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot,
                (source, target) -> {
                    if (moves.incrementAndGet() == 2) {
                        throw new java.io.IOException("injected failure before new approved snapshot move");
                    }
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                });

        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> workspace.install(IDENTITY, "New RU", "New EN", "New RU title", "New EN title",
                        "New RU description", "New EN description", referenceMap("new")));

        assertTrue(failure.getMessage().contains("injected failure"));
        dev.eugene.publicationexporter.candidate.CandidateSnapshot restored = workspace.read(IDENTITY).orElseThrow();
        assertEquals("Old RU", restored.ruBody());
        assertEquals("Old EN", restored.enBody());
    }

    @Test
    void freshInstanceRecoversFromInterruptedReplaceByRestoringBackup() throws Exception {
        FilesystemApprovedSnapshotWorkspace original = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        original.install(IDENTITY, "Old RU", "Old EN", "Old RU title", "Old EN title",
                "Old RU description", "Old EN description", referenceMap("old"));

        Path approvedDir = reviewRoot.resolve(IDENTITY.publicCollection()).resolve(IDENTITY.publicId())
                .resolve("approved");
        Path backupDir = approvedDir.resolveSibling("approved-backup-" + java.util.UUID.randomUUID());
        Files.move(approvedDir, backupDir, StandardCopyOption.ATOMIC_MOVE);

        FilesystemApprovedSnapshotWorkspace freshInstance = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        dev.eugene.publicationexporter.candidate.CandidateSnapshot recovered =
                freshInstance.read(IDENTITY).orElseThrow();

        assertEquals("Old RU", recovered.ruBody());
        assertEquals("Old EN", recovered.enBody());
        assertTrue(Files.notExists(backupDir), "stale backup should be cleaned up by recovery");
    }

    @Test
    void freshInstanceKeepsCompleteNewSnapshotAndCleansStaleBackup() throws Exception {
        FilesystemApprovedSnapshotWorkspace original = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
        original.install(IDENTITY, "Old RU", "Old EN", "Old RU title", "Old EN title",
                "Old RU description", "Old EN description", referenceMap("old"));

        Path approvedDir = reviewRoot.resolve(IDENTITY.publicCollection()).resolve(IDENTITY.publicId())
                .resolve("approved");
        Path backupDir = approvedDir.resolveSibling("approved-backup-" + java.util.UUID.randomUUID());
        Files.move(approvedDir, backupDir, StandardCopyOption.ATOMIC_MOVE);
        ReferenceMap newReferenceMap = referenceMap("new");
        Files.createDirectories(approvedDir);
        Files.writeString(approvedDir.resolve("ru.md"), "New RU", StandardCharsets.UTF_8);
        Files.writeString(approvedDir.resolve("en.md"), "New EN", StandardCharsets.UTF_8);
        Files.writeString(approvedDir.resolve("ru.title"), "New RU title", StandardCharsets.UTF_8);
        Files.writeString(approvedDir.resolve("en.title"), "New EN title", StandardCharsets.UTF_8);
        Files.writeString(approvedDir.resolve("ru.description"), "New RU description", StandardCharsets.UTF_8);
        Files.writeString(approvedDir.resolve("en.description"), "New EN description", StandardCharsets.UTF_8);
        Files.writeString(approvedDir.resolve("references.json"), ReferenceMapCodec.write(newReferenceMap),
                StandardCharsets.UTF_8);

        FilesystemApprovedSnapshotWorkspace freshInstance = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        dev.eugene.publicationexporter.candidate.CandidateSnapshot recovered =
                freshInstance.read(IDENTITY).orElseThrow();

        assertEquals("New RU", recovered.ruBody());
        assertEquals("New EN", recovered.enBody());
        assertEquals(newReferenceMap, recovered.referenceMap());
        assertTrue(Files.notExists(backupDir), "stale backup should be cleaned up by recovery");
    }

    @Test
    void noStagingDirectoryIsLeftBehindAfterASuccessfulInstall() throws Exception {
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        workspace.install(IDENTITY, "RU", "EN", "RU title", "EN title", "RU description.",
                "EN description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));

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
                        ReferenceMap.empty(escapingIdentity, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")));

        assertTrue(Files.notExists(freshRoot));
    }

    @Test
    void installRejectsSymlinkedChildEscapingReviewRoot() throws Exception {
        Files.createSymbolicLink(reviewRoot.resolve("blog"), outsideRoot);
        FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

        assertThrows(IllegalStateException.class,
                () -> workspace.install(IDENTITY, "RU", "EN", "RU title", "EN title",
                        "RU description.", "EN description.",
                        ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")));

        assertTrue(Files.notExists(outsideRoot.resolve("my-essay/approved")));
        try (var entries = Files.list(reviewRoot)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith("approved-staging-")));
        }
    }

    private static ReferenceMap referenceMap(String suffix) {
        return ReferenceMap.empty(IDENTITY, "ru-hash-" + suffix, "en-hash-" + suffix,
                "ru-title-hash-" + suffix, "en-title-hash-" + suffix,
                "ru-description-hash-" + suffix, "en-description-hash-" + suffix);
    }
}
