package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        Path candidateDir = reviewRoot.resolve("blog").resolve("my-essay").resolve("candidate");
        assertEquals("RU body", Files.readString(candidateDir.resolve("ru.md")));
        assertEquals("EN body", Files.readString(candidateDir.resolve("en.md")));
        assertTrue(Files.readString(candidateDir.resolve("references.json")).contains("\"ruHash\":\"ru-hash\""));
    }

    @Test
    void installCreatesTheReviewRootWhenItDoesNotYetExist() throws Exception {
        Path freshRoot = reviewRoot.resolve("not-created-yet");
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(freshRoot);

        workspace.install(IDENTITY, "RU", "EN", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        assertTrue(Files.exists(freshRoot.resolve("blog/my-essay/candidate/ru.md")));
    }

    @Test
    void noStagingDirectoryIsLeftBehindAfterASuccessfulInstall() throws Exception {
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);

        workspace.install(IDENTITY, "RU", "EN", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

        try (var entries = Files.list(reviewRoot)) {
            long stagingLeftovers = entries
                    .filter(path -> path.getFileName().toString().startsWith("candidate-staging-"))
                    .count();
            assertEquals(0, stagingLeftovers);
        }
    }

    @Test
    void installRejectsNullBodyBeforeCreatingTheReviewRoot() {
        Path freshRoot = reviewRoot.resolve("not-created-for-null-input");
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(freshRoot);

        assertThrows(NullPointerException.class,
                () -> workspace.install(IDENTITY, null, "EN", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash")));

        assertTrue(Files.notExists(freshRoot));
    }

    @Test
    void installRejectsCollectionParentSegmentBeforeAnyWrite() {
        Path freshRoot = reviewRoot.resolve("fresh-review-root");
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(freshRoot);
        PublicationIdentity escapingIdentity = PublicationIdentity.of("..", "essay", "escaped-collection");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> workspace.install(escapingIdentity, "RU", "EN",
                        ReferenceMap.empty(escapingIdentity, "ru-hash", "en-hash")));

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
                () -> workspace.install(escapingIdentity, "RU", "EN",
                        ReferenceMap.empty(escapingIdentity, "ru-hash", "en-hash")));

        assertTrue(Files.notExists(freshRoot));
        assertTrue(Files.notExists(reviewRoot.resolve("escaped-id")));
    }

    @Test
    void installRejectsSymlinkedChildEscapingReviewRoot() throws Exception {
        Files.createSymbolicLink(reviewRoot.resolve("blog"), outsideRoot);
        FilesystemCandidateWorkspace workspace = new FilesystemCandidateWorkspace(reviewRoot);

        assertThrows(IllegalStateException.class,
                () -> workspace.install(IDENTITY, "RU", "EN",
                        ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash")));

        assertTrue(Files.notExists(outsideRoot.resolve("my-essay/candidate")));
        try (var entries = Files.list(reviewRoot)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith("candidate-staging-")));
        }
    }
}
