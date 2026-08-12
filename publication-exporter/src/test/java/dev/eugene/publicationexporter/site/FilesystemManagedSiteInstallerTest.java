package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.LegacyCandidateSnapshotFixture;
import dev.eugene.publicationexporter.fs.StagedDirectoryInstall;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
    private static final PublicationIdentity NOTE_IDENTITY = PublicationIdentity.of("blog", "note", "my-note");
    private static final PublicationIdentity BOOK_IDENTITY =
            PublicationIdentity.of("bibliography", "book", "the-lean-startup");
    private static final PublicationIdentity CONCEPT_IDENTITY =
            PublicationIdentity.of("concepts", "concept", "bounded-context");
    private static final PublicationIdentity OTHER_IDENTITY =
            PublicationIdentity.of("blog", "essay", "another-essay");
    private static final CandidateSnapshot SNAPSHOT = LegacyCandidateSnapshotFixture.of(
            "# RU body", "# EN body", "RU title", "EN title", "RU description.", "EN description.",
            ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));
    private static final CandidateSnapshot NOTE_SNAPSHOT = LegacyCandidateSnapshotFixture.of(
            "# RU note body", "# EN note body", "RU note title", "EN note title",
            "RU note description.", "EN note description.",
            ReferenceMap.empty(NOTE_IDENTITY, "note-ru-hash", "note-en-hash",
                    "note-ru-title-hash", "note-en-title-hash",
                    "note-ru-description-hash", "note-en-description-hash"));
    private static final CandidateSnapshot BOOK_SNAPSHOT = CandidateSnapshot.of(
            "# RU book body",
            "# EN book body",
            List.of(
                    PublicField.of("title", "RU book title"),
                    PublicField.of("description", "RU book description."),
                    PublicField.of("use", "RU use"),
                    PublicField.of("boundary", "RU boundary")),
            List.of(
                    PublicField.of("title", "EN book title"),
                    PublicField.of("description", "EN book description."),
                    PublicField.of("use", "EN use"),
                    PublicField.of("boundary", "EN boundary")),
            """
                    authors:
                      - "Eric Ries"
                    publication: "Crown Business"
                    publicationDate: "2011-09-13"
                    readingStatus: "finished"
                    """,
            ReferenceMap.empty(
                    BOOK_IDENTITY,
                    "book-ru-hash",
                    "book-en-hash",
                    ContentHash.sha256Hex(PublicFieldsCodec.write(List.of(
                            PublicField.of("title", "RU book title"),
                            PublicField.of("description", "RU book description."),
                            PublicField.of("use", "RU use"),
                            PublicField.of("boundary", "RU boundary")))),
                    ContentHash.sha256Hex(PublicFieldsCodec.write(List.of(
                            PublicField.of("title", "EN book title"),
                            PublicField.of("description", "EN book description."),
                            PublicField.of("use", "EN use"),
                            PublicField.of("boundary", "EN boundary")))),
                    ContentHash.sha256Hex("""
                            authors:
                              - "Eric Ries"
                            publication: "Crown Business"
                            publicationDate: "2011-09-13"
                            readingStatus: "finished"
                            """)));
    private static final CandidateSnapshot REPLACEMENT_SNAPSHOT = LegacyCandidateSnapshotFixture.of(
            "# Replacement RU body", "# Replacement EN body",
            "Replacement RU title", "Replacement EN title",
            "Replacement RU description.", "Replacement EN description.",
            ReferenceMap.empty(IDENTITY,
                    "replacement-ru-hash", "replacement-en-hash",
                    "replacement-ru-title-hash", "replacement-en-title-hash",
                    "replacement-ru-description-hash", "replacement-en-description-hash"));
    private static final CandidateSnapshot UNWRITABLE_SNAPSHOT = LegacyCandidateSnapshotFixture.of(
            "\uD800", "unused", "title", "title", "description", "description",
            ReferenceMap.empty(IDENTITY,
                    "unwritable-ru-hash", "unwritable-en-hash",
                    "unwritable-ru-title-hash", "unwritable-en-title-hash",
                    "unwritable-ru-description-hash", "unwritable-en-description-hash"));
    private static final CandidateSnapshot OTHER_SNAPSHOT = LegacyCandidateSnapshotFixture.of(
            "# Other RU body", "# Other EN body", "Other RU title", "Other EN title",
            "Other RU description.", "Other EN description.",
            ReferenceMap.empty(OTHER_IDENTITY, "other-ru-hash", "other-en-hash",
                    "other-ru-title-hash", "other-en-title-hash",
                    "other-ru-description-hash", "other-en-description-hash"));

    @TempDir
    Path siteRoot;

    @Test
    void approvedStructuredSnapshotInstallsStructuredDataInBothSiteLocales() throws Exception {
        CandidateSnapshot prepared = CandidateSnapshot.of(
                "RU body", "EN body",
                List.of(PublicField.of("title", "RU title"), PublicField.of("description", "RU description")),
                List.of(PublicField.of("title", "EN title"), PublicField.of("description", "EN description")),
                "relationships:\n  - target: note-1\n",
                ReferenceMap.empty(IDENTITY, ContentHash.sha256Hex("RU body"), ContentHash.sha256Hex("EN body"),
                        ContentHash.sha256Hex(PublicFieldsCodec.write(List.of(
                                PublicField.of("title", "RU title"), PublicField.of("description", "RU description")))),
                        ContentHash.sha256Hex(PublicFieldsCodec.write(List.of(
                                PublicField.of("title", "EN title"), PublicField.of("description", "EN description")))),
                        ContentHash.sha256Hex("relationships:\n  - target: note-1\n")));
        Path reviewRoot = siteRoot.resolve("review");
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.create(reviewRoot);
        approved.install(IDENTITY, prepared);

        CandidateSnapshot approvedSnapshot = approved.read(IDENTITY).orElseThrow();
        new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, approvedSnapshot);

        String ru = Files.readString(siteRoot.resolve("src/content/blog/ru/my-essay.md"));
        String en = Files.readString(siteRoot.resolve("src/content/blog/en/my-essay.md"));
        assertTrue(ru.contains("relationships:\n  - target: note-1\n---\n"));
        assertTrue(en.contains("relationships:\n  - target: note-1\n---\n"));
    }

    @Test
    void installWritesBothLocaleFilesAndTheManifestIntoAbsentManagedRoots() throws Exception {
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);

        installer.install(IDENTITY, SNAPSHOT);

        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        assertTrue(Files.exists(ruFile));
        assertTrue(Files.exists(enFile));
        assertEquals("---\n"
                + "id: \"my-essay\"\n"
                + "title: \"RU title\"\n"
                + "description: \"RU description.\"\n"
                + "publish: true\n"
                + "contentType: \"essay\"\n"
                + "language: \"ru\"\n"
                + "sourceLanguage: \"ru\"\n"
                + "sourceHash: \"ru-hash\"\n"
                + "translationStatus: \"source\"\n"
                + "---\n"
                + "# RU body", Files.readString(ruFile, StandardCharsets.UTF_8));
        assertEquals("---\n"
                + "id: \"my-essay\"\n"
                + "title: \"EN title\"\n"
                + "description: \"EN description.\"\n"
                + "publish: true\n"
                + "contentType: \"essay\"\n"
                + "language: \"en\"\n"
                + "sourceLanguage: \"ru\"\n"
                + "sourceHash: \"ru-hash\"\n"
                + "translationStatus: \"generated\"\n"
                + "translationOf: \"my-essay\"\n"
                + "---\n"
                + "# EN body", Files.readString(enFile, StandardCharsets.UTF_8));
        assertTrue(Files.exists(siteRoot.resolve(".astro-export/release-provenance.json")));
    }

    @Test
    void installProjectsBlogNoteContentTypeWithTheSameSharedFieldSetAsEssay() throws Exception {
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);

        installer.install(NOTE_IDENTITY, NOTE_SNAPSHOT);

        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-note.md");
        assertEquals("---\n"
                + "id: \"my-note\"\n"
                + "title: \"RU note title\"\n"
                + "description: \"RU note description.\"\n"
                + "publish: true\n"
                + "contentType: \"note\"\n"
                + "language: \"ru\"\n"
                + "sourceLanguage: \"ru\"\n"
                + "sourceHash: \"note-ru-hash\"\n"
                + "translationStatus: \"source\"\n"
                + "---\n"
                + "# RU note body", Files.readString(ruFile, StandardCharsets.UTF_8));
    }

    @Test
    void installProjectsBibliographyBookIntoLibraryFilesWithTranslatedAndInvariantMetadata() throws Exception {
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);

        installer.install(BOOK_IDENTITY, BOOK_SNAPSHOT);

        Path ruFile = siteRoot.resolve("src/content/bibliography/ru/the-lean-startup.md");
        Path enFile = siteRoot.resolve("src/content/bibliography/en/the-lean-startup.md");
        assertTrue(Files.exists(ruFile));
        assertTrue(Files.exists(enFile));
        assertEquals("---\n"
                + "id: \"the-lean-startup\"\n"
                + "title: \"RU book title\"\n"
                + "description: \"RU book description.\"\n"
                + "use: \"RU use\"\n"
                + "boundary: \"RU boundary\"\n"
                + "publish: true\n"
                + "contentType: \"book\"\n"
                + "language: \"ru\"\n"
                + "sourceLanguage: \"ru\"\n"
                + "sourceHash: \"book-ru-hash\"\n"
                + "translationStatus: \"source\"\n"
                + "authors:\n"
                + "  - \"Eric Ries\"\n"
                + "publication: \"Crown Business\"\n"
                + "publicationDate: \"2011-09-13\"\n"
                + "readingStatus: \"finished\"\n"
                + "---\n"
                + "# RU book body", Files.readString(ruFile, StandardCharsets.UTF_8));
        assertEquals("---\n"
                + "id: \"the-lean-startup\"\n"
                + "title: \"EN book title\"\n"
                + "description: \"EN book description.\"\n"
                + "use: \"EN use\"\n"
                + "boundary: \"EN boundary\"\n"
                + "publish: true\n"
                + "contentType: \"book\"\n"
                + "language: \"en\"\n"
                + "sourceLanguage: \"ru\"\n"
                + "sourceHash: \"book-ru-hash\"\n"
                + "translationStatus: \"generated\"\n"
                + "translationOf: \"the-lean-startup\"\n"
                + "authors:\n"
                + "  - \"Eric Ries\"\n"
                + "publication: \"Crown Business\"\n"
                + "publicationDate: \"2011-09-13\"\n"
                + "readingStatus: \"finished\"\n"
                + "---\n"
                + "# EN book body", Files.readString(enFile, StandardCharsets.UTF_8));
        assertFalse(Files.readString(ruFile, StandardCharsets.UTF_8).contains("selectedQuote"));
        assertFalse(Files.readString(enFile, StandardCharsets.UTF_8).contains("selectedQuote"));
    }

    @Test
    void installProjectsConceptListsBackIntoYamlBlocks() throws Exception {
        List<PublicField> ruFields = List.of(
                PublicField.of("title", "RU concept title"),
                PublicField.of("description", "RU concept description."),
                PublicField.of("notThis", "RU not this"),
                PublicField.of("relations[0].name", "RU first name"),
                PublicField.of("relations[0].relation", "RU first relation"),
                PublicField.of("relations[1].name", "RU second name"),
                PublicField.of("relations[1].relation", "RU second relation"),
                PublicField.of("examples[0]", "RU first example"),
                PublicField.of("examples[1]", "RU second example"));
        List<PublicField> enFields = List.of(
                PublicField.of("title", "EN concept title"),
                PublicField.of("description", "EN concept description."),
                PublicField.of("notThis", "EN not this"),
                PublicField.of("relations[0].name", "EN first name"),
                PublicField.of("relations[0].relation", "EN first relation"),
                PublicField.of("relations[1].name", "EN second name"),
                PublicField.of("relations[1].relation", "EN second relation"),
                PublicField.of("examples[0]", "EN first example"),
                PublicField.of("examples[1]", "EN second example"));
        CandidateSnapshot snapshot = CandidateSnapshot.of(
                "# RU concept body", "# EN concept body", ruFields, enFields, "",
                ReferenceMap.empty(
                        CONCEPT_IDENTITY,
                        "concept-ru-hash",
                        "concept-en-hash",
                        ContentHash.sha256Hex(PublicFieldsCodec.write(ruFields)),
                        ContentHash.sha256Hex(PublicFieldsCodec.write(enFields)),
                        ContentHash.sha256Hex("")));

        new FilesystemManagedSiteInstaller(siteRoot).install(CONCEPT_IDENTITY, snapshot);

        assertEquals("---\n"
                + "id: \"bounded-context\"\n"
                + "title: \"EN concept title\"\n"
                + "description: \"EN concept description.\"\n"
                + "notThis: \"EN not this\"\n"
                + "relations:\n"
                + "  - name: \"EN first name\"\n"
                + "    relation: \"EN first relation\"\n"
                + "  - name: \"EN second name\"\n"
                + "    relation: \"EN second relation\"\n"
                + "examples:\n"
                + "  - \"EN first example\"\n"
                + "  - \"EN second example\"\n"
                + "publish: true\n"
                + "contentType: \"concept\"\n"
                + "language: \"en\"\n"
                + "sourceLanguage: \"ru\"\n"
                + "sourceHash: \"concept-ru-hash\"\n"
                + "translationStatus: \"generated\"\n"
                + "translationOf: \"bounded-context\"\n"
                + "---\n"
                + "# EN concept body", Files.readString(
                        siteRoot.resolve("src/content/concepts/en/bounded-context.md"), StandardCharsets.UTF_8));
    }

    @Test
    void renderRejectsListIndexThatExceedsIntegerRangeWithItsFieldKey() {
        String fieldKey = "relations[2147483648].name";

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BracketIndexedFields.render(
                        List.of(PublicField.of(fieldKey, "value")), ignored -> { }));

        assertTrue(failure.getMessage().contains(fieldKey));
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
    void nextInstallRecoversAnInterruptedReplacementFromBackupStorageOutsideManagedRoots() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path ruBackup = newManagedBackupPath(ruFile);
        new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT);
        Files.move(ruFile, ruBackup);
        assertFalse(Files.exists(ruFile));
        assertTrue(Files.isRegularFile(ruBackup));

        new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT);

        assertTrue(Files.readString(ruFile).endsWith(SNAPSHOT.ruBody()));
        assertFalse(Files.exists(ruBackup));
    }

    @Test
    void recoveryRollsBackRuWhenItsSwapCompletedBeforeEnStarted() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        Path ruBackup = newManagedBackupPath(ruFile);
        Path manifest = siteRoot.resolve(".astro-export/release-provenance.json");
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);
        installer.install(IDENTITY, SNAPSHOT);
        String oldRu = Files.readString(ruFile);
        String oldEn = Files.readString(enFile);
        String oldManifest = Files.readString(manifest);

        installer.install(IDENTITY, REPLACEMENT_SNAPSHOT);
        String newRu = Files.readString(ruFile);

        Files.writeString(ruFile, oldRu);
        Files.writeString(enFile, oldEn);
        Files.writeString(manifest, oldManifest);
        Files.copy(ruFile, ruBackup);
        Files.writeString(ruFile, newRu);

        ManagedSiteInstallationFailedAfterRecoveryException failure = assertThrows(
                ManagedSiteInstallationFailedAfterRecoveryException.class,
                () -> new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, UNWRITABLE_SNAPSHOT));
        assertTrue(failure.getCause() instanceof UncheckedIOException);

        assertEquals(oldRu, Files.readString(ruFile));
        assertEquals(oldEn, Files.readString(enFile));
        assertEquals(oldManifest, Files.readString(manifest));
        assertFalse(Files.exists(ruBackup));
    }

    @Test
    void provenanceWriteFailureDuringRecoveryLeavesBackupForNextRecoveryAttempt() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        Path ruBackup = newManagedBackupPath(ruFile);
        Path manifest = siteRoot.resolve(".astro-export/release-provenance.json");
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);
        installer.install(IDENTITY, SNAPSHOT);
        String oldRu = Files.readString(ruFile);
        String oldEn = Files.readString(enFile);
        String oldManifest = Files.readString(manifest);

        installer.install(IDENTITY, REPLACEMENT_SNAPSHOT);
        String newRu = Files.readString(ruFile);

        Files.writeString(ruFile, oldRu);
        Files.writeString(enFile, oldEn);
        Files.copy(ruFile, ruBackup);
        Files.writeString(ruFile, newRu);
        Files.delete(manifest);
        Files.createDirectory(manifest);

        assertThrows(UncheckedIOException.class,
                () -> installer.install(IDENTITY, UNWRITABLE_SNAPSHOT));

        assertEquals(oldRu, Files.readString(ruFile));
        assertEquals(oldEn, Files.readString(enFile));
        assertTrue(Files.isRegularFile(ruBackup));
        assertTrue(Files.isDirectory(manifest));

        Files.delete(manifest);

        ManagedSiteInstallationFailedAfterRecoveryException failure = assertThrows(
                ManagedSiteInstallationFailedAfterRecoveryException.class,
                () -> installer.install(IDENTITY, UNWRITABLE_SNAPSHOT));
        assertTrue(failure.getCause() instanceof UncheckedIOException);

        assertEquals(oldRu, Files.readString(ruFile));
        assertEquals(oldEn, Files.readString(enFile));
        assertEquals(oldManifest, Files.readString(manifest));
        assertFalse(Files.exists(ruBackup));
    }

    @Test
    void recoveryKeepsCanonicalGenerationWhenProvenanceMatchesAndDeletesCleanupDebris() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        Path ruBackup = newManagedBackupPath(ruFile);
        Path manifest = siteRoot.resolve(".astro-export/release-provenance.json");
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);
        installer.install(IDENTITY, SNAPSHOT);
        String oldRu = Files.readString(ruFile);
        installer.install(IDENTITY, REPLACEMENT_SNAPSHOT);
        String completedRu = Files.readString(ruFile);
        String completedEn = Files.readString(enFile);
        String completedManifest = Files.readString(manifest);
        Files.writeString(ruBackup, oldRu);

        ManagedSiteInstallationFailedAfterRecoveryException failure = assertThrows(
                ManagedSiteInstallationFailedAfterRecoveryException.class,
                () -> new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, UNWRITABLE_SNAPSHOT));
        assertTrue(failure.getCause() instanceof UncheckedIOException);

        assertEquals(completedRu, Files.readString(ruFile));
        assertEquals(completedEn, Files.readString(enFile));
        assertEquals(completedManifest, Files.readString(manifest));
        assertFalse(Files.exists(ruBackup));
    }

    @Test
    void failedInstallAfterRecoveryReportsThatRecoveryAlreadyRestoredThePriorGeneration() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        Path enDirectory = enFile.getParent();
        Path ruBackup = newManagedBackupPath(ruFile);
        Path manifest = siteRoot.resolve(".astro-export/release-provenance.json");
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);
        installer.install(IDENTITY, SNAPSHOT);
        String oldRu = Files.readString(ruFile);
        String oldEn = Files.readString(enFile);
        String oldManifest = Files.readString(manifest);
        Files.copy(ruFile, ruBackup);
        Files.writeString(ruFile, "interrupted replacement bytes");

        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(enDirectory);
        try {
            Files.setPosixFilePermissions(enDirectory, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));

            ManagedSiteInstallationFailedAfterRecoveryException failure = assertThrows(
                    ManagedSiteInstallationFailedAfterRecoveryException.class,
                    () -> installer.installWithOutcome(IDENTITY, REPLACEMENT_SNAPSHOT));

            assertTrue(failure.getCause() instanceof UncheckedIOException);
            assertEquals(oldRu, Files.readString(ruFile));
            assertEquals(oldEn, Files.readString(enFile));
            assertEquals(oldManifest, Files.readString(manifest));
            assertFalse(Files.exists(ruBackup));
        } finally {
            Files.setPosixFilePermissions(enDirectory, originalPermissions);
        }
    }

    @Test
    void recoveryFailsLoudlyWhenProvenanceMismatchesWithoutAnyBackup() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT);
        String oldEn = Files.readString(enFile);
        Files.writeString(ruFile, "unclassifiable canonical bytes");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, REPLACEMENT_SNAPSHOT));

        assertTrue(failure.getMessage().contains("provenance does not match the current managed tree"));
        assertEquals("unclassifiable canonical bytes", Files.readString(ruFile));
        assertEquals(oldEn, Files.readString(enFile));
    }

    @Test
    void recoveryRejectsMultipleBackupsForOneLocale() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path firstBackup = newManagedBackupPath(ruFile);
        Path secondBackup = newManagedBackupPath(ruFile);
        new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT);
        String canonicalBefore = Files.readString(ruFile);
        Files.copy(ruFile, firstBackup);
        Files.copy(ruFile, secondBackup);

        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, REPLACEMENT_SNAPSHOT));

        assertTrue(failure.getMessage().contains("Multiple locale recovery backups exist"));
        assertEquals(canonicalBefore, Files.readString(ruFile));
        assertTrue(Files.isRegularFile(firstBackup));
        assertTrue(Files.isRegularFile(secondBackup));
    }

    @Test
    void secondInstallReplacesThePriorGeneration() throws Exception {
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);
        installer.install(IDENTITY, SNAPSHOT);

        installer.install(IDENTITY, REPLACEMENT_SNAPSHOT);

        assertTrue(Files.readString(siteRoot.resolve("src/content/blog/ru/my-essay.md"))
                .endsWith(REPLACEMENT_SNAPSHOT.ruBody()));
        assertTrue(Files.readString(siteRoot.resolve("src/content/blog/en/my-essay.md"))
                .endsWith(REPLACEMENT_SNAPSHOT.enBody()));
    }

    @Test
    void anInstallWithOnlyOneExistingLocaleFileFailsLoudlyWithoutRecoveryBackups() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        Files.createDirectories(ruFile.getParent());
        Files.writeString(ruFile, "pre-existing", StandardCharsets.UTF_8);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT));

        assertTrue(failure.getMessage().contains("provenance does not match the current managed tree"));
        assertEquals("pre-existing", Files.readString(ruFile));
        assertFalse(Files.exists(enFile));
    }

    @Test
    void anEnMoveFailureRollsBackRuAndAllowsTheSameIdentityToRetry() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        Path enDirectory = enFile.getParent();
        Files.createDirectories(enDirectory);
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(enDirectory);
        try {
            Files.setPosixFilePermissions(enDirectory, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));

            assertThrows(UncheckedIOException.class,
                    () -> new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT));
        } finally {
            Files.setPosixFilePermissions(enDirectory, originalPermissions);
        }

        assertFalse(Files.exists(ruFile));

        new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT);

        assertTrue(Files.isRegularFile(ruFile));
        assertTrue(Files.isRegularFile(enFile));
        assertTrue(Files.readString(ruFile).endsWith(SNAPSHOT.ruBody()));
        assertTrue(Files.readString(enFile).endsWith(SNAPSHOT.enBody()));
    }

    @Test
    void unreadableProvenanceWithoutBackupsFailsLoudlyAndAllowsRetryAfterRepair() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        Path manifest = siteRoot.resolve(".astro-export/release-provenance.json");
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);
        installer.install(IDENTITY, SNAPSHOT);
        String oldRu = Files.readString(ruFile);
        String oldEn = Files.readString(enFile);
        String oldManifest = Files.readString(manifest);
        Files.delete(manifest);
        Files.createDirectories(manifest);
        assertTrue(Files.isDirectory(manifest));

        assertThrows(ManagedSiteRecoveryException.class,
                () -> installer.install(IDENTITY, REPLACEMENT_SNAPSHOT));

        assertEquals(oldRu, Files.readString(ruFile));
        assertEquals(oldEn, Files.readString(enFile));
        assertTrue(Files.isDirectory(manifest));

        Files.delete(manifest);
        Files.writeString(manifest, oldManifest);
        installer.install(IDENTITY, REPLACEMENT_SNAPSHOT);

        assertTrue(Files.readString(ruFile).endsWith(REPLACEMENT_SNAPSHOT.ruBody()));
        assertTrue(Files.readString(enFile).endsWith(REPLACEMENT_SNAPSHOT.enBody()));
        assertTrue(Files.isRegularFile(manifest));
    }

    @Test
    void rollbackFailureReleasesTheSiteWideLockAndAllowsRetry() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path ruDirectory = ruFile.getParent();
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        Path manifest = siteRoot.resolve(".astro-export/release-provenance.json");
        createSparsePayload(siteRoot.resolve("public/assets/vault/slow-payload.bin"), 64 * 1024 * 1024);
        new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT);
        String oldEn = Files.readString(enFile);

        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(ruDirectory);
        ExecutorService installers = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> firstInstall = installers.submit(() -> {
                try {
                    new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, REPLACEMENT_SNAPSHOT);
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });

            awaitFileEndsWith(ruFile, REPLACEMENT_SNAPSHOT.ruBody());
            Files.setPosixFilePermissions(ruDirectory, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            Files.delete(manifest);
            Files.createDirectory(manifest);

            Throwable firstFailure = resultOf(firstInstall);
            assertTrue(firstFailure instanceof UncheckedIOException,
                    () -> "expected manifest IOException but got " + firstFailure);
            assertTrue(Files.isRegularFile(ruFile), "failed RU rollback must leave the orphan visible");
            assertEquals(oldEn, Files.readString(enFile), "EN rollback should still complete independently");
        } finally {
            Files.setPosixFilePermissions(ruDirectory, originalPermissions);
            installers.shutdownNow();
        }

        Files.delete(manifest);

        new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, REPLACEMENT_SNAPSHOT);

        assertTrue(Files.readString(ruFile).endsWith(REPLACEMENT_SNAPSHOT.ruBody()));
        assertTrue(Files.readString(enFile).endsWith(REPLACEMENT_SNAPSHOT.enBody()));
        assertTrue(Files.isRegularFile(manifest));
    }

    @Test
    void aPathEscapingSiteRootIsRejected() {
        PublicationIdentity escaping = PublicationIdentity.of("../../../outside", "essay", "my-essay");
        CandidateSnapshot snapshot = LegacyCandidateSnapshotFixture.of(
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
    void failingCommitKeepsTheSiteLockThroughManifestWorkAndRejectsADifferentIdentity() throws Exception {
        Path ruDirectory = siteRoot.resolve("src/content/blog/ru");
        Path ruFile = ruDirectory.resolve("my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        Path manifest = siteRoot.resolve(".astro-export/release-provenance.json");
        createSparsePayload(siteRoot.resolve("public/assets/vault/slow-payload.bin"), 64 * 1024 * 1024);
        new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT);
        String oldRu = Files.readString(ruFile);
        String oldEn = Files.readString(enFile);

        ExecutorService installers = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> firstInstall = installers.submit(() -> {
                try {
                    new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, REPLACEMENT_SNAPSHOT);
                    return null;
                } catch (Throwable error) {
                    return error;
                }
            });

            awaitFileEndsWith(ruFile, REPLACEMENT_SNAPSHOT.ruBody());
            Files.delete(manifest);
            Files.createDirectory(manifest);
            assertThrows(SiteAlreadyInstalledException.class,
                    () -> new FilesystemManagedSiteInstaller(siteRoot).install(OTHER_IDENTITY, OTHER_SNAPSHOT));

            Throwable firstFailure = resultOf(firstInstall);
            assertTrue(firstFailure instanceof UncheckedIOException,
                    () -> "expected manifest IOException but got " + firstFailure);
            assertEquals(oldRu, Files.readString(ruFile));
            assertEquals(oldEn, Files.readString(enFile));
            assertFalse(Files.exists(siteRoot.resolve("src/content/blog/ru/another-essay.md")));
            assertFalse(Files.exists(siteRoot.resolve("src/content/blog/en/another-essay.md")));
        } finally {
            installers.shutdownNow();
        }
    }

    @Test
    void anExistingUnlockedInstallationLockFileDoesNotBlockInstallation() throws Exception {
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        Path manifest = siteRoot.resolve(".astro-export/release-provenance.json");
        Path installationLock = siteRoot.resolve(".astro-export/install-locks/.site.installing");
        Files.createDirectories(installationLock.getParent());
        Files.writeString(installationLock, "stale lock-file bytes");

        new FilesystemManagedSiteInstaller(siteRoot).install(IDENTITY, SNAPSHOT);

        assertTrue(Files.isRegularFile(installationLock));
        assertTrue(Files.isRegularFile(ruFile));
        assertTrue(Files.isRegularFile(enFile));
        assertTrue(Files.isRegularFile(manifest));
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

    private Path newManagedBackupPath(Path localeFile) throws Exception {
        Path mirroredLocale = siteRoot.resolve(".astro-export/managed-backups")
                .resolve(siteRoot.relativize(localeFile));
        Files.createDirectories(mirroredLocale.getParent());
        return mirroredLocale.resolveSibling(mirroredLocale.getFileName() + ".backup-" + UUID.randomUUID());
    }

    private static void createSparsePayload(Path file, int size) throws Exception {
        Files.createDirectories(file.getParent());
        try (var channel = Files.newByteChannel(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(size - 1L);
            channel.write(ByteBuffer.wrap(new byte[] { 0 }));
        }
    }

    private static void awaitFileEndsWith(Path path, String suffix) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path) && Files.readString(path).endsWith(suffix)) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("timed out waiting for " + path + " to end with " + suffix);
    }

}
