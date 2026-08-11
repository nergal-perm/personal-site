package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemCandidateWorkspaceTest {

    @TempDir
    Path reviewRoot;

    @TempDir
    Path outsideRoot;

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void installWritesAllThreeFilesAtTheirFinalPath() throws Exception {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash");

        workspace.install(IDENTITY, CandidateSnapshot.of("RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap), List.of());

        Path candidateDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("candidate");
        assertEquals("RU body", Files.readString(candidateDir.resolve("ru.md")));
        assertEquals("EN body", Files.readString(candidateDir.resolve("en.md")));
        assertEquals("RU title", Files.readString(candidateDir.resolve("ru.title")));
        assertEquals("EN title", Files.readString(candidateDir.resolve("en.title")));
        assertEquals("RU description.", Files.readString(candidateDir.resolve("ru.description")));
        assertEquals("EN description.", Files.readString(candidateDir.resolve("en.description")));
        assertTrue(Files.readString(candidateDir.resolve("references.json")).contains("\"ruHash\":\"ru-hash\""));
    }

    @Test
    void installCreatesTheReviewRootWhenItDoesNotYetExist() throws Exception {
        Path freshRoot = reviewRoot.resolve("not-created-yet");
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(freshRoot);

        workspace.install(IDENTITY, CandidateSnapshot.of("RU", "EN", "Title", "EN Title", "Description.", "EN Description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")), List.of());

        assertTrue(Files.exists(freshRoot.resolve("blog/my-essay/candidate/ru.md")));
    }

    @Test
    void noStagingDirectoryIsLeftBehindAfterASuccessfulInstall() throws Exception {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);

        workspace.install(IDENTITY, CandidateSnapshot.of("RU", "EN", "Title", "EN Title", "Description.", "EN Description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")), List.of());

        try (var entries = Files.list(reviewRoot)) {
            long stagingLeftovers = entries
                    .filter(path -> path.getFileName().toString().startsWith("candidate-staging-"))
                    .count();
            assertEquals(0, stagingLeftovers);
        }
    }

    @Test
    void replacingCandidateInstallsNewSnapshotAndRemovesBackup() throws Exception {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        workspace.install(IDENTITY, CandidateSnapshot.of("Old RU", "Old EN", "Old RU title", "Old EN title",
                "Old RU description", "Old EN description", referenceMap("old")), List.of());

        workspace.install(IDENTITY, CandidateSnapshot.of("New RU", "New EN", "New RU title", "New EN title",
                "New RU description", "New EN description", referenceMap("new")), List.of());

        CandidateSnapshot installed = workspace.read(IDENTITY).orElseThrow();
        assertEquals("New RU", installed.ruBody());
        assertEquals("New EN", installed.enBody());
        try (var entries = Files.list(reviewRoot.resolve("blog/my-essay"))) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith("candidate-backup-")));
        }
    }

    @Test
    void installWritesAssetBytesUnderTheCandidateDirectoryAndTheyStillReadCorrectlyAfterReplacement() throws Exception {
        CandidateWorkspace workspace = CandidateWorkspace.create(reviewRoot);
        CandidateAsset asset = CandidateAsset.of("abc123.png", "image-bytes".getBytes(StandardCharsets.UTF_8));

        workspace.install(IDENTITY,
                CandidateSnapshot.of("RU body", "EN body", "RU title", "EN title", "RU desc.", "EN desc.",
                        ReferenceMap.empty(IDENTITY, "h1", "h2", "h3", "h4", "h5", "h6")),
                List.of(asset));

        Path assetPath = reviewRoot.resolve("blog/my-essay/candidate/assets/abc123.png");
        assertArrayEquals("image-bytes".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(assetPath));

        workspace.install(IDENTITY,
                CandidateSnapshot.of("RU body v2", "EN body v2", "RU title", "EN title", "RU desc.", "EN desc.",
                        ReferenceMap.empty(IDENTITY, "h1", "h2", "h3", "h4", "h5", "h6")),
                List.of(asset));

        assertArrayEquals("image-bytes".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(assetPath));
    }

    @Test
    void assetTraversalIsRejectedWithoutReplacingTheExistingCandidate() throws Exception {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        workspace.install(IDENTITY,
                CandidateSnapshot.of("Old RU", "Old EN", "Old RU title", "Old EN title",
                        "Old RU description", "Old EN description", referenceMap("old")),
                List.of());
        CandidateAsset escapingAsset = CandidateAsset.of(
                "../escape.txt", "escaped content".getBytes(StandardCharsets.UTF_8));

        assertThrows(CandidateWorkspaceConfinementException.class,
                () -> workspace.install(IDENTITY,
                        CandidateSnapshot.of("New RU", "New EN", "New RU title", "New EN title",
                                "New RU description", "New EN description", referenceMap("new")),
                        List.of(escapingAsset)));

        CandidateSnapshot installed = workspace.read(IDENTITY).orElseThrow();
        assertEquals("Old RU", installed.ruBody());
        assertEquals("Old EN", installed.enBody());
        assertTrue(Files.notExists(reviewRoot.resolve("blog/my-essay/candidate/escape.txt")));
    }

    @Test
    void assetConfinementFailureRemovesTheStagingDirectory() throws Exception {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        CandidateAsset escapingAsset = CandidateAsset.of(
                "../escape.txt", "escaped content".getBytes(StandardCharsets.UTF_8));

        assertThrows(CandidateWorkspaceConfinementException.class,
                () -> workspace.install(IDENTITY,
                        CandidateSnapshot.of("RU", "EN", "RU title", "EN title",
                                "RU description", "EN description", referenceMap("rejected")),
                        List.of(escapingAsset)));

        try (var entries = Files.list(reviewRoot)) {
            assertFalse(entries.anyMatch(
                    path -> path.getFileName().toString().startsWith("candidate-staging-")));
        }
    }

    @Test
    void failedNewMoveRestoresFullyReadableOldCandidate() throws Exception {
        new FilesystemCandidateWorkspace(reviewRoot).install(IDENTITY,
                CandidateSnapshot.of("Old RU", "Old EN", "Old RU title", "Old EN title",
                        "Old RU description", "Old EN description", referenceMap("old")), List.of());
        AtomicInteger moves = new AtomicInteger();
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot, (source, target) -> {
            if (moves.incrementAndGet() == 2) {
                throw new java.io.IOException("injected failure before new candidate move");
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        });

        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> workspace.install(IDENTITY,
                        CandidateSnapshot.of("New RU", "New EN", "New RU title", "New EN title",
                                "New RU description", "New EN description", referenceMap("new")), List.of()));

        assertTrue(failure.getMessage().contains("injected failure"));
        CandidateSnapshot restored = workspace.read(IDENTITY).orElseThrow();
        assertEquals("Old RU", restored.ruBody());
        assertEquals("Old EN", restored.enBody());
        assertEquals("Old RU title", restored.ruTitle());
        assertEquals("Old EN title", restored.enTitle());
        assertEquals("Old RU description", restored.ruDescription());
        assertEquals("Old EN description", restored.enDescription());
        try (var entries = Files.list(reviewRoot.resolve("blog/my-essay"))) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith("candidate-backup-")));
        }
    }

    @Test
    void installRejectsNullContentBeforeCreatingTheReviewRoot() {
        Path freshRoot = reviewRoot.resolve("not-created-for-null-input");
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(freshRoot);

        assertThrows(NullPointerException.class,
                () -> workspace.install(IDENTITY, null, List.of()));

        assertTrue(Files.notExists(freshRoot));
    }

    @Test
    void installRejectsCollectionParentSegmentBeforeAnyWrite() {
        Path freshRoot = reviewRoot.resolve("fresh-review-root");
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(freshRoot);
        PublicationIdentity escapingIdentity = PublicationIdentity.of("..", "essay", "escaped-collection");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> workspace.install(escapingIdentity,
                        CandidateSnapshot.of("RU", "EN", "Title", "EN Title", "Description.", "EN Description.",
                                ReferenceMap.empty(escapingIdentity, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")), List.of()));

        assertTrue(failure.getMessage().contains("escapes review root"));
        assertTrue(Files.notExists(freshRoot));
        assertTrue(Files.notExists(reviewRoot.resolve("escaped-collection")));
    }

    @Test
    void installRejectsPublicIdParentSegmentBeforeAnyWrite() {
        Path freshRoot = reviewRoot.resolve("fresh-review-root");
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(freshRoot);
        PublicationIdentity escapingIdentity = PublicationIdentity.of("blog", "essay", "../../escaped-id");

        assertThrows(IllegalStateException.class,
                () -> workspace.install(escapingIdentity,
                        CandidateSnapshot.of("RU", "EN", "Title", "EN Title", "Description.", "EN Description.",
                                ReferenceMap.empty(escapingIdentity, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")), List.of()));

        assertTrue(Files.notExists(freshRoot));
        assertTrue(Files.notExists(reviewRoot.resolve("escaped-id")));
    }

    @Test
    void installRejectsSymlinkedChildEscapingReviewRoot() throws Exception {
        Files.createSymbolicLink(reviewRoot.resolve("blog"), outsideRoot);
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);

        assertThrows(IllegalStateException.class,
                () -> workspace.install(IDENTITY,
                        CandidateSnapshot.of("RU", "EN", "Title", "EN Title", "Description.", "EN Description.",
                                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")), List.of()));

        assertTrue(Files.notExists(outsideRoot.resolve("my-essay/candidate")));
        try (var entries = Files.list(reviewRoot)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith("candidate-staging-")));
        }
    }

    @Test
    void findIsAbsentBeforeInstall() {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }

    @Test
    void findReturnsAbsolutePathsToTheInstalledFiles() throws Exception {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        workspace.install(IDENTITY, CandidateSnapshot.of("RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")), List.of());

        Optional<CandidatePaths> found = workspace.find(IDENTITY);

        assertTrue(found.isPresent());
        Path candidateDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("candidate");
        assertEquals(candidateDir.resolve("ru.md").toRealPath(), found.get().ruPath().toRealPath());
        assertEquals(candidateDir.resolve("en.md").toRealPath(), found.get().enPath().toRealPath());
        assertTrue(found.get().ruPath().isAbsolute());
        assertTrue(found.get().enPath().isAbsolute());
    }

    @Test
    void findIsAbsentForADifferentIdentityAfterInstall() {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        workspace.install(IDENTITY, CandidateSnapshot.of("RU", "EN", "Title", "EN Title", "Description.", "EN Description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")), List.of());
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");

        assertEquals(Optional.empty(), workspace.find(otherIdentity));
    }

    @Test
    void readIsAbsentBeforeInstall() {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void readReturnsTheInstalledBodiesAndReferenceMap() {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash");
        workspace.install(IDENTITY, CandidateSnapshot.of("RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap), List.of());

        Optional<CandidateSnapshot> read = workspace.read(IDENTITY);

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
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        workspace.install(IDENTITY, CandidateSnapshot.of("RU body", "EN body", "Title", "EN Title", "Description.", "EN Description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")), List.of());
        Path enPath = reviewRoot.resolve("blog/my-essay/candidate/en.md");
        Path outsideEnPath = outsideRoot.resolve("outside-en.md");
        Files.writeString(outsideEnPath, "outside EN body");
        Files.delete(enPath);
        Files.createSymbolicLink(enPath, outsideEnPath);

        assertThrows(CandidateWorkspaceConfinementException.class, () -> workspace.read(IDENTITY));
    }

    @Test
    void readRejectsSymlinkedReferenceMapFileEscapingReviewRoot() throws Exception {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        workspace.install(IDENTITY, CandidateSnapshot.of("RU body", "EN body", "Title", "EN Title", "Description.", "EN Description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")), List.of());
        Path referencesPath = reviewRoot.resolve("blog/my-essay/candidate/references.json");
        Path outsideReferencesPath = outsideRoot.resolve("outside-references.json");
        Files.writeString(outsideReferencesPath, "{}");
        Files.delete(referencesPath);
        Files.createSymbolicLink(referencesPath, outsideReferencesPath);

        assertThrows(CandidateWorkspaceConfinementException.class, () -> workspace.read(IDENTITY));
    }

    @Test
    void readIsAbsentForADifferentIdentityAfterInstall() {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        workspace.install(IDENTITY, CandidateSnapshot.of("RU", "EN", "Title", "EN Title", "Description.", "EN Description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")), List.of());
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");

        assertEquals(Optional.empty(), workspace.read(otherIdentity));
    }

    @Test
    void readIsAbsentForADifferentContentTypeAtTheSameCandidatePath() {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        workspace.install(IDENTITY, CandidateSnapshot.of("RU", "EN", "Title", "EN Title", "Description.", "EN Description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash")), List.of());
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "article", "my-essay");

        assertEquals(Optional.empty(), workspace.read(otherIdentity));
    }

    @Test
    void readReturnsTheInstalledTitleAndDescription() throws Exception {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash");
        workspace.install(IDENTITY, CandidateSnapshot.of("RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap), List.of());

        Optional<CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU title", read.get().ruTitle());
        assertEquals("EN title", read.get().enTitle());
        assertEquals("RU description.", read.get().ruDescription());
        assertEquals("EN description.", read.get().enDescription());
    }

    private ReferenceMap referenceMap(String generation) {
        return ReferenceMap.empty(IDENTITY,
                generation + "-ru", generation + "-en",
                generation + "-ru-title", generation + "-en-title",
                generation + "-ru-description", generation + "-en-description");
    }
}
