package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.fs.StagedDirectoryInstall;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemManagedSiteInstallerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final CandidateSnapshot SNAPSHOT = CandidateSnapshot.of(
            "# RU body", "# EN body", "RU title", "EN title", "RU description.", "EN description.",
            ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));

    @TempDir
    Path siteRoot;

    @Test
    void installWritesBothLocaleFilesAndTheManifestIntoAbsentManagedRoots() throws Exception {
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);

        installer.install(IDENTITY, SNAPSHOT);

        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        assertTrue(Files.exists(ruFile));
        assertTrue(Files.exists(enFile));
        assertEquals("---\n"
                + "id: my-essay\n"
                + "title: RU title\n"
                + "description: RU description.\n"
                + "publish: true\n"
                + "contentType: essay\n"
                + "language: ru\n"
                + "sourceLanguage: ru\n"
                + "sourceHash: ru-hash\n"
                + "translationStatus: source\n"
                + "---\n"
                + "# RU body", Files.readString(ruFile, StandardCharsets.UTF_8));
        assertEquals("---\n"
                + "id: my-essay\n"
                + "title: EN title\n"
                + "description: EN description.\n"
                + "publish: true\n"
                + "contentType: essay\n"
                + "language: en\n"
                + "sourceLanguage: ru\n"
                + "sourceHash: ru-hash\n"
                + "translationStatus: generated\n"
                + "translationOf: my-essay\n"
                + "---\n"
                + "# EN body", Files.readString(enFile, StandardCharsets.UTF_8));
        assertTrue(Files.exists(siteRoot.resolve(".astro-export/release-provenance.json")));
    }

    @Test
    void installPreservesExistingFilesInSharedLocaleDirectories() throws Exception {
        Path existingRu = siteRoot.resolve("src/content/blog/ru/existing.md");
        Path existingEn = siteRoot.resolve("src/content/blog/en/existing.md");
        Files.createDirectories(existingRu.getParent());
        Files.createDirectories(existingEn.getParent());
        Files.writeString(existingRu, "existing ru", StandardCharsets.UTF_8);
        Files.writeString(existingEn, "existing en", StandardCharsets.UTF_8);

        new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT);

        assertEquals("existing ru", Files.readString(existingRu, StandardCharsets.UTF_8));
        assertEquals("existing en", Files.readString(existingEn, StandardCharsets.UTF_8));
    }

    @Test
    void aSecondInstallForTheSameIdentityThrowsWithoutReplacingEitherLocaleFile() throws Exception {
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);
        installer.install(IDENTITY, SNAPSHOT);
        String ruBefore = Files.readString(siteRoot.resolve("src/content/blog/ru/my-essay.md"));
        String enBefore = Files.readString(siteRoot.resolve("src/content/blog/en/my-essay.md"));

        assertThrows(SiteAlreadyInstalledException.class, () -> installer.install(IDENTITY, SNAPSHOT));

        assertEquals(ruBefore, Files.readString(siteRoot.resolve("src/content/blog/ru/my-essay.md")));
        assertEquals(enBefore, Files.readString(siteRoot.resolve("src/content/blog/en/my-essay.md")));
    }

    @Test
    void anInstallWithOnlyOneExistingLocaleFileIsRejectedBeforeWritingTheOther() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Files.createDirectories(ruFile.getParent());
        Files.writeString(ruFile, "pre-existing", StandardCharsets.UTF_8);

        assertThrows(SiteAlreadyInstalledException.class,
                () -> new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT));

        assertFalse(Files.exists(siteRoot.resolve("src/content/blog/en/my-essay.md")));
        assertEquals("pre-existing", Files.readString(ruFile, StandardCharsets.UTF_8));
    }

    @Test
    void aPathEscapingSiteRootIsRejected() {
        PublicationIdentity escaping = PublicationIdentity.of("../../../outside", "essay", "my-essay");
        CandidateSnapshot snapshot = CandidateSnapshot.of(
                "ru", "en", "ru title", "en title", "ru description", "en description",
                ReferenceMap.empty(escaping, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));

        assertThrows(ManagedSiteInstallerConfinementException.class,
                () -> new FilesystemManagedSiteInstaller(siteRoot).install(escaping, snapshot));
    }

    @Test
    void aSymlinkedManagedParentIsRejected() throws Exception {
        Path outside = siteRoot.resolveSibling(siteRoot.getFileName() + "-outside");
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(siteRoot.resolve("src"), outside);

            assertThrows(ManagedSiteInstallerConfinementException.class,
                    () -> new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT));
            assertFalse(Files.exists(outside.resolve("content/blog/ru/my-essay.md")));
        } finally {
            Files.deleteIfExists(siteRoot.resolve("src"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aSiteRootReplacedWithOutsideSymlinkAfterInstallerCreationIsRejected() throws Exception {
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);
        Path outside = siteRoot.resolveSibling(siteRoot.getFileName() + "-outside");
        Files.createDirectories(outside);
        Files.delete(siteRoot);
        Files.createSymbolicLink(siteRoot, outside);
        try {
            assertThrows(ManagedSiteInstallerConfinementException.class,
                    () -> installer.install(IDENTITY, SNAPSHOT));
            assertFalse(Files.exists(outside.resolve("src/content/blog/ru/my-essay.md")));
        } finally {
            Files.deleteIfExists(siteRoot);
            StagedDirectoryInstall.deleteRecursively(outside);
        }
    }

    @Test
    void stagedInstallDoesNotReresolveTheManagedSitesAlreadyCanonicalRoot() throws Exception {
        Path canonicalAbsentRoot = siteRoot.toRealPath().resolve("absent-site-root");
        Path outside = siteRoot.resolveSibling(siteRoot.getFileName() + "-outside");
        Files.createDirectories(outside);
        Files.createSymbolicLink(canonicalAbsentRoot, outside);
        try {
            StagedDirectoryInstall stagedInstall =
                    StagedDirectoryInstall.rootedAtCanonical(canonicalAbsentRoot);

            assertEquals(canonicalAbsentRoot, stagedInstall.canonicalRoot());
        } finally {
            Files.deleteIfExists(canonicalAbsentRoot);
            StagedDirectoryInstall.deleteRecursively(outside);
        }
    }

    @Test
    void anExistingSymlinkedComponentWithinTheSiteUsesItsRealPath() throws Exception {
        Path realProvenanceDirectory = siteRoot.resolve("real-provenance");
        Files.createDirectories(realProvenanceDirectory);
        Files.createSymbolicLink(siteRoot.resolve(".astro-export"), realProvenanceDirectory);

        new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT);

        Path manifest = realProvenanceDirectory.resolve("release-provenance.json");
        assertTrue(Files.isRegularFile(manifest));
        assertEquals(manifest.toRealPath(), siteRoot.resolve(".astro-export/release-provenance.json").toRealPath());
    }

    @Test
    void concurrentInstallForTheSameIdentityHasOneWinnerAndOneCleanAlreadyInstalledLoser() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Throwable> install = () -> {
            ready.countDown();
            start.await();
            try {
                new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT);
                return null;
            } catch (Throwable error) {
                return error;
            }
        };

        ExecutorService installers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> outcomes = List.of(installers.submit(install), installers.submit(install));
            ready.await();
            start.countDown();
            List<Throwable> failures = outcomes.stream().map(FilesystemManagedSiteInstallerTest::resultOf)
                    .filter(error -> error != null)
                    .toList();

            assertEquals(1, failures.size());
            assertTrue(failures.get(0) instanceof SiteAlreadyInstalledException,
                    () -> "expected SiteAlreadyInstalledException but got " + failures.get(0));
        } finally {
            installers.shutdownNow();
        }
    }

    @Test
    void creatingAnInstallerForAnAbsentNestedRootDoesNotWriteOrThrow() {
        Path nestedRoot = siteRoot.resolve("nested");

        ManagedSiteInstaller installer = ManagedSiteInstaller.create(nestedRoot);

        assertTrue(installer instanceof FilesystemManagedSiteInstaller);
        assertFalse(Files.exists(nestedRoot));
    }

    private static Throwable resultOf(Future<Throwable> outcome) {
        try {
            return outcome.get();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
