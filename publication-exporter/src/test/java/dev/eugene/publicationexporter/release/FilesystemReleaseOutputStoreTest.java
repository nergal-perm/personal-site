package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemReleaseOutputStoreTest {

    @TempDir
    Path outputRoot;

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final ReleaseProvenance PROVENANCE =
            ReleaseProvenance.of(IDENTITY, "ru-hash", "en-hash", "ru-hash", "en-hash");

    @Test
    void installWritesAllThreeFilesAtTheirFinalPath() throws Exception {
        FilesystemReleaseOutputStore store = new FilesystemReleaseOutputStore(outputRoot);

        store.install(IDENTITY, "RU body", "EN body", PROVENANCE);

        Path releaseDir = outputRoot.resolve("blog").resolve("my-essay").resolve("release");
        assertEquals("RU body", Files.readString(releaseDir.resolve("ru.md")));
        assertEquals("EN body", Files.readString(releaseDir.resolve("en.md")));
        assertTrue(Files.readString(releaseDir.resolve("release-provenance.json")).contains("\"approvedRuHash\":\"ru-hash\""));
    }

    @Test
    void aSecondInstallForTheSameIdentityThrowsAndLeavesTheFirstReleaseIntact() throws Exception {
        FilesystemReleaseOutputStore store = new FilesystemReleaseOutputStore(outputRoot);
        store.install(IDENTITY, "RU body", "EN body", PROVENANCE);

        assertThrows(ReleaseAlreadyExistsException.class,
                () -> store.install(IDENTITY, "RU body 2", "EN body 2", PROVENANCE));

        Path releaseDir = outputRoot.resolve("blog").resolve("my-essay").resolve("release");
        assertEquals("RU body", Files.readString(releaseDir.resolve("ru.md")));
    }

    @Test
    void installRejectsAReviewWorkspaceRootWithoutCreatingReleaseOrStagingContent() throws Exception {
        Path publicationDirectory = outputRoot.resolve("blog/my-essay");
        Path approvedDirectory = publicationDirectory.resolve("approved");
        Files.createDirectories(approvedDirectory);
        Files.writeString(approvedDirectory.resolve("ru.md"), "approved RU body");
        FilesystemReleaseOutputStore store = new FilesystemReleaseOutputStore(outputRoot);

        assertThrows(ReleaseOutputRootNotEmptyException.class,
                () -> store.install(IDENTITY, "RU", "EN", PROVENANCE));

        assertTrue(Files.notExists(publicationDirectory.resolve("release")));
        try (var entries = Files.list(outputRoot)) {
            assertEquals(0, entries
                    .filter(path -> path.getFileName().toString().startsWith("release-staging-"))
                    .count());
        }
    }

    @Test
    void noStagingDirectoryIsLeftBehindAfterASuccessfulInstall() throws Exception {
        FilesystemReleaseOutputStore store = new FilesystemReleaseOutputStore(outputRoot);

        store.install(IDENTITY, "RU", "EN", PROVENANCE);

        try (var entries = Files.list(outputRoot)) {
            long stagingLeftovers = entries
                    .filter(path -> path.getFileName().toString().startsWith("release-staging-"))
                    .count();
            assertEquals(0, stagingLeftovers);
        }
    }

    @Test
    void installRejectsCollectionParentSegmentBeforeAnyWrite() {
        Path freshRoot = outputRoot.resolve("fresh-output-root");
        FilesystemReleaseOutputStore store = new FilesystemReleaseOutputStore(freshRoot);
        PublicationIdentity escapingIdentity = PublicationIdentity.of("..", "essay", "escaped-collection");

        assertThrows(IllegalStateException.class,
                () -> store.install(escapingIdentity, "RU", "EN",
                        ReleaseProvenance.of(escapingIdentity, "ru", "en", "ru", "en")));

        assertTrue(Files.notExists(freshRoot));
    }

    @Test
    void confinementFailureAfterStagingWritesRemovesTheStagingDirectory(@TempDir Path escapedRoot)
            throws Exception {
        FilesystemReleaseOutputStore store = new FilesystemReleaseOutputStore(outputRoot);
        ExecutorService installer = Executors.newSingleThreadExecutor();
        try {
            Future<?> installation = installer.submit(() -> store.install(
                    IDENTITY, "Ж".repeat(16 * 1024 * 1024), "EN", PROVENANCE));
            Path stagingDirectory = awaitStagingDirectory();
            Files.createSymbolicLink(outputRoot.resolve("blog"), escapedRoot);

            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> installation.get(10, TimeUnit.SECONDS));

            assertInstanceOf(ReleaseOutputStoreConfinementException.class, failure.getCause());
            assertTrue(Files.notExists(escapedRoot.resolve("my-essay/release")));
            assertTrue(Files.notExists(stagingDirectory));
        } finally {
            installer.shutdownNow();
            installer.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void buildingTheSameApprovedStateTwiceIntoTwoFreshRootsProducesIdenticalOutput(@TempDir Path secondOutputRoot)
            throws Exception {
        FilesystemReleaseOutputStore first = new FilesystemReleaseOutputStore(outputRoot);
        FilesystemReleaseOutputStore second = new FilesystemReleaseOutputStore(secondOutputRoot);

        first.install(IDENTITY, "RU body", "EN body", PROVENANCE);
        second.install(IDENTITY, "RU body", "EN body", PROVENANCE);

        Path firstReleaseDir = outputRoot.resolve("blog").resolve("my-essay").resolve("release");
        Path secondReleaseDir = secondOutputRoot.resolve("blog").resolve("my-essay").resolve("release");
        assertEquals(Files.readString(firstReleaseDir.resolve("ru.md")), Files.readString(secondReleaseDir.resolve("ru.md")));
        assertEquals(Files.readString(firstReleaseDir.resolve("en.md")), Files.readString(secondReleaseDir.resolve("en.md")));
        assertEquals(
                Files.readString(firstReleaseDir.resolve("release-provenance.json")),
                Files.readString(secondReleaseDir.resolve("release-provenance.json")));
    }

    private Path awaitStagingDirectory() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (var entries = Files.list(outputRoot)) {
                var stagingDirectory = entries
                        .filter(path -> path.getFileName().toString().startsWith("release-staging-"))
                        .findFirst();
                if (stagingDirectory.isPresent()) {
                    return stagingDirectory.get();
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Timed out waiting for a release staging directory");
    }
}
