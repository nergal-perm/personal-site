package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.intake.NoteIntake;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.Occurrence;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.translation.NullTranslationWorker;
import dev.eugene.publicationexporter.translation.TranslationJob;
import dev.eugene.publicationexporter.translation.TranslationOutcome;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultAssetReader;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.NullWorkflowStatusEditor;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PrepareHandlerTest {

    @TempDir
    Path temporaryRoot;

    private static final String VALID_ESSAY = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            title: My Essay
            description: A valid description.
            ---
            # My Essay

            Plain prose body.""";

    private static final String VALID_BOOK = """
            ---
            publish: true
            publicCollection: bibliography
            publicContentType: book
            publicId: the-lean-startup
            id: 8f2c-the-lean-startup
            title: The Lean Startup
            description: A valid description.
            authors:
              - Eric Ries
            publication: Crown Business
            publicationDate: 2011-09-13
            readingStatus: finished
            use: Explains how to test demand before scaling a product bet.
            boundary: Only the startup-method parts are directly relevant.
            ---
            # The Lean Startup

            A reading note body.""";

    private static final String VALID_ALBUM = """
            ---
            publish: true
            publicCollection: music
            publicContentType: album
            publicId: kind-of-blue
            id: 8f2c-kind-of-blue
            title: Kind of Blue
            description: A valid album description.
            artist: Miles Davis
            work: Kind of Blue
            context: A modal jazz record.
            association: Blue note.
            format: LP
            care: Listen with headphones.
            listenFor:
              - modal harmony
              - ensemble interaction
            releaseDate: 1959-08-17
            genreTags:
              - jazz
              - modal
            streamingUrl: "https://example.test/kind-of-blue"
            bandcampEmbedUrl: "https://bandcamp.test/embed/kind-of-blue"
            ---
            # Kind of Blue

            An album body.""";

    private static final String VALID_CURATED_PAGE_BODY = """
            ## Кратко

            Кратко.

            ## Eyebrow

            Бровь.

            ## Лид

            Лид.

            ## Принципы

            ### Первый

            Принцип.

            ## Колофон

            Колофон.
            """;

    private static final String VALID_CURATED_PAGE = """
            ---
            publish: true
            publicCollection: editorial
            publicContentType: curated_page
            publicId: about
            editorialPage: about
            id: source-about
            title: About
            ---
            """ + VALID_CURATED_PAGE_BODY;

    private static final String CHANGED_ALBUM = """
            ---
            publish: true
            publicCollection: music
            publicContentType: album
            publicId: kind-of-blue
            id: 8f2c-kind-of-blue
            title: Kind of Blue
            description: A valid album description.
            artist: Kamasi Washington
            work: The Epic
            context: A modal jazz record.
            association: Blue note.
            format: LP
            care: Listen with headphones.
            listenFor:
              - modal harmony
              - ensemble interaction
            releaseDate: 2015-05-29
            genreTags:
              - contemporary-jazz
              - experimental
            streamingUrl: "https://example.test/the-epic"
            bandcampEmbedUrl: "https://bandcamp.test/embed/the-epic"
            ---
            # Kind of Blue

            An album body.""";

    @Test
    void successfulPrepareWritesReadyForReviewWorkflowStatus() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, VALID_ESSAY));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

        handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertEquals("ready_for_review", editor.currentValue(path, "workflowStatus"));
    }

    @Test
    void commentOnlyEditDuringTranslationWritesReadyForReviewForCurrentSource() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        String originalEssay = essayWithBody("# My Essay\n\nPlain prose body.\n\n\n\nMore prose.");
        String editedEssay = essayWithBody(
                "# My Essay\n\nPlain prose body.\n\n%% added while translating %%\n\nMore prose.");
        AtomicInteger reads = new AtomicInteger();
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return reads.incrementAndGet() == 1 ? originalEssay : editedEssay;
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, editedEssay));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertEquals("ready_for_review", response.status());
        assertEquals("ready_for_review", editor.currentValue(path, "workflowStatus"));
    }

    @Test
    void translationFailureWritesTranslationFailedWorkflowStatus() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, VALID_ESSAY));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNullFailing("worker crashed"),
                new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

        handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertEquals("translation_failed", editor.currentValue(path, "workflowStatus"));
    }

    @Test
    void stalePrepareWritesStaleWorkflowStatus() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        AtomicInteger reads = new AtomicInteger();
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return reads.incrementAndGet() <= 1 ? VALID_ESSAY : essayWithBody("Changed while translating.");
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(
                Map.of(path, essayWithBody("Changed while translating.")));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertEquals("stale", response.status());
        assertEquals("stale", editor.currentValue(path, "workflowStatus"));
        assertEquals(3, reads.get());
    }

    @Test
    void structuredMetadataChangeDuringTranslationIsStale() {
        VaultRelativePath path = VaultRelativePath.of("blog/latency-budget-is-fiction.md");
        String originalClaim = claimWithStructuredData("""
                supports:
                  - label: Original supporting claim
                """);
        String editedClaim = claimWithStructuredData("""
                supports:
                  - label: Edited supporting claim
                """);
        AtomicInteger reads = new AtomicInteger();
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return reads.incrementAndGet() == 1 ? originalClaim : editedClaim;
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, editedClaim));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "English claim body.",
                        claimFields(
                                "A fixed latency budget is fiction",
                                "A valid English description.",
                                "A fixed latency budget is usually the wrong abstraction.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertEquals("stale", response.status());
        assertEquals("stale", editor.currentValue(path, "workflowStatus"));
        assertTrue(workspace.installed().isEmpty());
        assertEquals(3, reads.get());
    }

    @Test
    void prepareBibliographyBookInstallsTranslatedFieldsAndInvariantStructuredData() {
        VaultRelativePath path = VaultRelativePath.of("bibliography/the-lean-startup.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_BOOK));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "Translated book body.",
                        bookFields(
                                "The Lean Startup",
                                "A valid English description.",
                                "Explains how to test demand before scaling a product bet.",
                                "Only the startup-method parts are directly relevant.")),
                workspace,
                ApprovedSnapshotWorkspace.createNull(),
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals(1, workspace.installed().size());
        NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);
        assertEquals(PublicationIdentity.of("bibliography", "book", "the-lean-startup"), installed.identity());
        assertEquals(bookFields(
                "The Lean Startup",
                "A valid description.",
                "Explains how to test demand before scaling a product bet.",
                "Only the startup-method parts are directly relevant."),
                installed.ruFields());
        assertEquals(bookFields(
                "The Lean Startup",
                "A valid English description.",
                "Explains how to test demand before scaling a product bet.",
                "Only the startup-method parts are directly relevant."),
                installed.enFields());
        String expectedStructuredData = bookStructuredData(
                "Crown Business",
                "2011-09-13",
                null,
                null,
                "finished");
        assertEquals(expectedStructuredData, installed.structuredData());
        assertEquals(ContentHash.sha256Hex(expectedStructuredData),
                installed.referenceMap().structuredDataHash());
    }

    @Test
    void prepareMusicAlbumInstallsTranslatedFieldsAndInvariantStructuredData() {
        VaultRelativePath path = VaultRelativePath.of("music/kind-of-blue.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ALBUM));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "Translated album body.",
                        albumFields(
                                "Kind of Blue",
                                "A valid English album description.",
                                "A modal jazz record.",
                                "Blue note.")),
                workspace,
                ApprovedSnapshotWorkspace.createNull(),
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals(1, workspace.installed().size());
        NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);
        assertEquals(PublicationIdentity.of("music", "album", "kind-of-blue"), installed.identity());
        assertEquals(albumFields(
                "Kind of Blue",
                "A valid album description.",
                "A modal jazz record.",
                "Blue note."), installed.ruFields());
        assertEquals(albumFields(
                "Kind of Blue",
                "A valid English album description.",
                "A modal jazz record.",
                "Blue note."), installed.enFields());
        String expectedStructuredData = albumStructuredData(
                "Miles Davis",
                "Kind of Blue",
                "1959-08-17",
                "https://example.test/kind-of-blue",
                "https://bandcamp.test/embed/kind-of-blue",
                List.of("jazz", "modal"));
        assertEquals(expectedStructuredData, installed.structuredData());
        assertEquals(ContentHash.sha256Hex(expectedStructuredData),
                installed.referenceMap().structuredDataHash());
    }

    @Test
    void changedBookInvariantMetadataSkipsApprovedMirrorAndBuildsFreshCandidate() {
        PublicationIdentity identity = PublicationIdentity.of("bibliography", "book", "the-lean-startup");
        String ruBody = "# The Lean Startup\n\nA reading note body.";
        String enBody = "Translated book body.";
        List<PublicField> russianFields = bookFields(
                "The Lean Startup",
                "A valid description.",
                "Explains how to test demand before scaling a product bet.",
                "Only the startup-method parts are directly relevant.");
        List<PublicField> englishFields = bookFields(
                "The Lean Startup",
                "A valid English description.",
                "Explains how to test demand before scaling a product bet.",
                "Only the startup-method parts are directly relevant.");
        String approvedStructuredData = bookStructuredData(
                "Portfolio",
                "2011-09-13",
                null,
                null,
                "finished");
        String currentStructuredData = bookStructuredData(
                "Crown Business",
                "2011-09-13",
                null,
                null,
                "finished");
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        approved.install(identity, CandidateSnapshot.of(
                ruBody, enBody, russianFields, englishFields, approvedStructuredData,
                referenceMap(identity, ruBody, enBody, russianFields, englishFields, approvedStructuredData)));
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success(enBody, englishFields));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        VaultRelativePath path = VaultRelativePath.of("bibliography/the-lean-startup.md");
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker,
                workspace,
                approved,
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(
                path,
                VaultReader.createNull(Map.of(path, VALID_BOOK)),
                VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals(1, worker.requested().size(),
                "a book invariant metadata edit must take the translation/review path, not mirror approval");
        assertEquals(currentStructuredData, workspace.installed().get(0).structuredData());
        assertEquals(ContentHash.sha256Hex(currentStructuredData),
                workspace.installed().get(0).referenceMap().structuredDataHash());
    }

    @Test
    void changedAlbumInvariantMetadataSkipsApprovedMirrorAndBuildsFreshCandidate() {
        PublicationIdentity identity = PublicationIdentity.of("music", "album", "kind-of-blue");
        String ruBody = "# Kind of Blue\n\nAn album body.";
        String enBody = "Translated album body.";
        String currentAlbum = VALID_ALBUM.replace(
                "https://bandcamp.test/embed/kind-of-blue",
                "https://bandcamp.test/embed/updated-kind-of-blue");
        List<PublicField> russianFields = albumFields(
                "Kind of Blue", "A valid album description.", "A modal jazz record.", "Blue note.");
        List<PublicField> englishFields = albumFields(
                "Kind of Blue", "A valid English album description.", "A modal jazz record.", "Blue note.");
        String approvedStructuredData = albumStructuredData(
                "Miles Davis", "Kind of Blue", "1959-08-17", "https://example.test/kind-of-blue",
                "https://bandcamp.test/embed/kind-of-blue", List.of("jazz", "modal"));
        String currentStructuredData = albumStructuredData(
                "Miles Davis", "Kind of Blue", "1959-08-17", "https://example.test/kind-of-blue",
                "https://bandcamp.test/embed/updated-kind-of-blue", List.of("jazz", "modal"));
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        approved.install(identity, CandidateSnapshot.of(
                ruBody, enBody, russianFields, englishFields, approvedStructuredData,
                referenceMap(identity, ruBody, enBody, russianFields, englishFields, approvedStructuredData)));
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success(enBody, englishFields));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        VaultRelativePath path = VaultRelativePath.of("music/kind-of-blue.md");
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker,
                workspace,
                approved,
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(
                path,
                VaultReader.createNull(Map.of(path, currentAlbum)),
                VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals(1, worker.requested().size(),
                "an album invariant metadata edit must take the translation/review path, not mirror approval");
        assertEquals(currentStructuredData, workspace.installed().get(0).structuredData());
        assertEquals(ContentHash.sha256Hex(currentStructuredData),
                workspace.installed().get(0).referenceMap().structuredDataHash());
    }

    @Test
    void changedCuratedPageSearchabilityBuildsFreshCandidate() {
        PublicationIdentity identity = PublicationIdentity.of("editorial", "curated_page", "about");
        String approvedStructuredData = "{\"searchable\":false,\"type\":\"about\"}";
        String currentStructuredData = "{\"searchable\":true,\"type\":\"about\"}";
        List<PublicField> russianFields = curatedPageFields("About");
        List<PublicField> englishFields = curatedPageFields("About (EN)");
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        approved.install(identity, CandidateSnapshot.of(
                VALID_CURATED_PAGE_BODY, "Translated curated page body.", russianFields, englishFields,
                approvedStructuredData,
                referenceMap(identity, VALID_CURATED_PAGE_BODY, "Translated curated page body.",
                        russianFields, englishFields, approvedStructuredData)));
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("Translated curated page body.", englishFields));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        VaultRelativePath path = VaultRelativePath.of("editorial/about.md");
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker,
                workspace,
                approved,
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(
                path,
                VaultReader.createNull(Map.of(path, VALID_CURATED_PAGE.replace(
                        "title: About\n", "title: About\npublicSearchable: true\n"))),
                VaultAssetReader.createNull());

        assertTrue(response.ok(), response.toString());
        assertEquals("ready_for_review", response.status());
        assertEquals(1, worker.requested().size(),
                "changing curated-page structuredData must build a fresh candidate");
        assertEquals(currentStructuredData, workspace.installed().get(0).structuredData());
        assertEquals(ContentHash.sha256Hex(currentStructuredData),
                workspace.installed().get(0).referenceMap().structuredDataHash());
    }

    @Test
    void changedAlbumInvariantMetadataDuringTranslationIsStale() throws Exception {
        VaultRelativePath path = VaultRelativePath.of("music/kind-of-blue.md");
        AtomicReference<String> source = new AtomicReference<>(VALID_ALBUM);
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return source.get();
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        CountDownLatch translationStarted = new CountDownLatch(1);
        CountDownLatch releaseTranslation = new CountDownLatch(1);
        TranslationWorker worker = (job, ruBody, ruFields) -> {
            translationStarted.countDown();
            await(releaseTranslation);
            return TranslationOutcome.success(
                    "Translated album body.",
                    albumFields("Kind of Blue", "A valid English album description.",
                            "A modal jazz record.", "Blue note."));
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker,
                workspace,
                ApprovedSnapshotWorkspace.createNull(),
                WorkflowStatusEditor.createNull());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BridgeResponse> response = executor.submit(
                    () -> handler.prepare(path, vaultReader, VaultAssetReader.createNull()));
            assertTrue(translationStarted.await(5, TimeUnit.SECONDS));
            source.set(CHANGED_ALBUM);
            releaseTranslation.countDown();

            assertEquals("stale", response.get(5, TimeUnit.SECONDS).status());
        } finally {
            executor.shutdownNow();
        }

        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void approvedBookWithMissingOptionalTranslatedFieldBuildsFreshCandidate() {
        PublicationIdentity identity = PublicationIdentity.of("bibliography", "book", "the-lean-startup");
        String ruBody = "# The Lean Startup\n\nA reading note body.";
        String enBody = "Translated book body.";
        List<PublicField> approvedRussianFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid description."),
                PublicField.of("use", "Explains how to test demand before scaling a product bet."));
        List<PublicField> approvedEnglishFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid English description."),
                PublicField.of("use", "Explains how to test demand before scaling a product bet."));
        String structuredData = bookStructuredData(
                "Portfolio",
                "2011-09-13",
                null,
                null,
                "finished");
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        approved.install(identity, CandidateSnapshot.of(
                ruBody, enBody, approvedRussianFields, approvedEnglishFields, structuredData,
                referenceMap(identity, ruBody, enBody, approvedRussianFields, approvedEnglishFields, structuredData)));
        List<PublicField> translatedFields = bookFields(
                "The Lean Startup",
                "A valid English description.",
                "Explains how to test demand before scaling a product bet.",
                "Only the startup-method parts are directly relevant.");
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success(enBody, translatedFields));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker,
                workspace,
                approved,
                WorkflowStatusEditor.createNull());
        VaultRelativePath path = VaultRelativePath.of("bibliography/the-lean-startup.md");

        BridgeResponse response = handler.prepare(
                path,
                VaultReader.createNull(Map.of(path, VALID_BOOK)),
                VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals(1, worker.requested().size(),
                "approved snapshots with a different optional translated field count must be retranslated");
        assertEquals(translatedFields, workspace.installed().get(0).enFields());
    }

    @Test
    void approvedBookWithDifferentOptionalTranslatedFieldKeyBuildsFreshCandidate() {
        PublicationIdentity identity = PublicationIdentity.of("bibliography", "book", "the-lean-startup");
        String ruBody = "# The Lean Startup\n\nA reading note body.";
        String enBody = "Translated book body.";
        List<PublicField> approvedRussianFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid description."),
                PublicField.of("use", "Same note"));
        List<PublicField> approvedEnglishFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid English description."),
                PublicField.of("use", "Same note"));
        String structuredData = bookStructuredData(
                "Portfolio",
                "2011-09-13",
                null,
                null,
                "finished");
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        approved.install(identity, CandidateSnapshot.of(
                ruBody, enBody, approvedRussianFields, approvedEnglishFields, structuredData,
                referenceMap(identity, ruBody, enBody, approvedRussianFields, approvedEnglishFields, structuredData)));
        List<PublicField> translatedFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid English description."),
                PublicField.of("boundary", "Same note"));
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success(enBody, translatedFields));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker,
                workspace,
                approved,
                WorkflowStatusEditor.createNull());
        VaultRelativePath path = VaultRelativePath.of("bibliography/the-lean-startup.md");

        BridgeResponse response = handler.prepare(
                path,
                VaultReader.createNull(Map.of(path, bookWithTranslatedField("boundary", "Same note"))),
                VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals(1, worker.requested().size(),
                "approved snapshots with a different optional translated field key must be retranslated");
        assertEquals(translatedFields, workspace.installed().get(0).enFields());
    }

    @Test
    void changedBookTranslatedFieldKeyWithSameValueIsStale() throws Exception {
        VaultRelativePath path = VaultRelativePath.of("bibliography/the-lean-startup.md");
        AtomicReference<String> source = new AtomicReference<>(bookWithTranslatedField("use", "Same note"));
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return source.get();
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        CountDownLatch translationStarted = new CountDownLatch(1);
        CountDownLatch releaseTranslation = new CountDownLatch(1);
        TranslationWorker worker = (job, ruBody, ruFields) -> {
            translationStarted.countDown();
            await(releaseTranslation);
            return TranslationOutcome.success(
                    "Translated book body.",
                    List.of(
                            PublicField.of("title", "The Lean Startup"),
                            PublicField.of("description", "A valid English description."),
                            PublicField.of("use", "Same note")));
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker,
                workspace,
                ApprovedSnapshotWorkspace.createNull(),
                WorkflowStatusEditor.createNull());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BridgeResponse> response = executor.submit(
                    () -> handler.prepare(path, vaultReader, VaultAssetReader.createNull()));
            assertTrue(translationStarted.await(5, TimeUnit.SECONDS));
            source.set(bookWithTranslatedField("boundary", "Same note"));
            releaseTranslation.countDown();

            assertEquals("stale", response.get(5, TimeUnit.SECONDS).status());
        } finally {
            executor.shutdownNow();
        }

        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void prepareBibliographyBookBlocksMissingTranslatedBookFields() {
        VaultRelativePath path = VaultRelativePath.of("bibliography/the-lean-startup.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_BOOK));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "Translated book body.",
                        fields("The Lean Startup", "A valid English description.")),
                workspace,
                ApprovedSnapshotWorkspace.createNull(),
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("translated field structure"));
        assertTrue(response.diagnostics().get(0).message().contains("use"));
        assertTrue(response.diagnostics().get(0).message().contains("boundary"));
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void unclosedCommentDuringFreshnessRecheckWritesNoWorkflowStatus() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        String essayWithUnclosedComment = essayWithBody("Public prose.\n\n%% private note is never closed");
        AtomicInteger reads = new AtomicInteger();
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return reads.incrementAndGet() == 1 ? VALID_ESSAY : essayWithUnclosedComment;
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, essayWithUnclosedComment));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("never closed"));
        assertTrue(workspace.installed().isEmpty());
        assertEquals(null, editor.currentValue(path, "workflowStatus"));
    }

    @Test
    void blockedWorkflowStatusWriteDoesNotChangePrepareResponse() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(
                Map.of(path, essayWithBody("A different source.")));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals(null, editor.currentValue(path, "workflowStatus"));
    }

    @Test
    void workflowStatusIoFailureDoesNotChangePrepareResponse() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        WorkflowStatusEditor editor = (notePath, expectedSourceHash, newValue) -> {
            throw new UncheckedIOException(new IOException("workflow status file unavailable"));
        };
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                new NullCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
    }

    @Test
    void knownNotesEnumerationIoFailureReturnsBlockedResponseWithoutInstallingCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return VALID_ESSAY;
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                throw new UncheckedIOException(new IOException("vault enumeration unavailable"));
            }
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("known-notes", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("vault enumeration unavailable"));
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void sourceIdentityLookupIoFailureReturnsBlockedResponseWithoutInstallingCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath privateTargetPath = VaultRelativePath.of("private-area/Secret Draft.md");
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return notePath.equals(path) || notePath.equals(privateTargetPath);
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                if (notePath.equals(privateTargetPath)) {
                    throw new UncheckedIOException(new IOException("private target unreadable"));
                }
                return """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        # My Essay

                        See [[private-area/Secret Draft]].""";
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of(path);
            }

            @Override
            public List<VaultRelativePath> listAllNotePaths() {
                return List.of(path, privateTargetPath);
            }
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", fields("Translated title", "Translated description.")));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker, workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("source-identity", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("private target unreadable"));
        assertTrue(workspace.installed().isEmpty());
        assertTrue(worker.requested().isEmpty());
    }

    @Test
    void sourceIdentityLookupIoFailureForPublicOnlyLinkReturnsBlockedResponseWithoutInstallingCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath publicTargetPath = VaultRelativePath.of("blog/public-target.md");
        String source = essayWithBody("See [[public-target]].");
        String publicTarget = VALID_ESSAY.replace("my-essay", "public-target");
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return notePath.equals(path) || notePath.equals(publicTargetPath);
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return notePath.equals(publicTargetPath) ? publicTarget : source;
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of(path, publicTargetPath);
            }

            @Override
            public List<VaultRelativePath> listAllNotePaths() {
                throw new UncheckedIOException(new IOException("source identity enumeration unavailable"));
            }
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", fields("Translated title", "Translated description.")));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker, workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("source-identity", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("source identity enumeration unavailable"));
        assertTrue(workspace.installed().isEmpty());
        assertTrue(worker.requested().isEmpty());
    }

    @Test
    void prepareWithNoPrivateTargetsSkipsPrivateIdentityScanForUnrelatedUnreadableNote() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath otherUnreadablePath = VaultRelativePath.of("private-area/Secret Draft.md");
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return notePath.equals(path) || notePath.equals(otherUnreadablePath);
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                if (notePath.equals(otherUnreadablePath)) {
                    throw new UncheckedIOException(new IOException("unrelated private note unreadable"));
                }
                return VALID_ESSAY;
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of(path);
            }

            @Override
            public List<VaultRelativePath> listAllNotePaths() {
                return List.of(path, otherUnreadablePath);
            }
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals(1, workspace.installed().size());
    }

    @Test
    void sourceIdentityLookupNoSuchElementExceptionIsReturnedAsBlockedResponse() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath privateTargetPath = VaultRelativePath.of("private-area/Secret Draft.md");
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return notePath.equals(path) || notePath.equals(privateTargetPath);
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                if (notePath.equals(privateTargetPath)) {
                    throw new NoSuchElementException("private target disappeared");
                }
                return """
                        ---
                        publish: true
                        publicCollection: blog
                        publicContentType: essay
                        publicId: my-essay
                        id: 8f2c-my-essay
                        title: My Essay
                        description: A valid description.
                        ---
                        # My Essay

                        See [[private-area/Secret Draft]].""";
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of(path);
            }

            @Override
            public List<VaultRelativePath> listAllNotePaths() {
                return List.of(path, privateTargetPath);
            }
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", fields("EN title", "EN description.")));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker, workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("source-identity", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("private target disappeared"));
        assertTrue(workspace.installed().isEmpty());
        assertTrue(worker.requested().isEmpty());
    }

    @Test
    void initialAssetResolutionIoFailureReturnsBlockedResponseWithoutInstallingCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                path, essayWithBody("# My Essay\n\n![[diagram.png]]")));
        VaultAssetReader failingAssetReader = reference -> {
            throw new UncheckedIOException(new IOException("asset storage unavailable"));
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, failingAssetReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("assets", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("asset storage unavailable"));
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void freshnessAssetResolutionIoFailureReturnsBlockedResponseWithoutInstallingCandidate() {
        byte[] imageBytes = "diagram".getBytes(StandardCharsets.UTF_8);
        String digest = ContentHash.sha256Hex(imageBytes);
        String resolvedBody = "# My Essay\n\n![diagram](/assets/vault/" + digest + ".png)";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                path, essayWithBody("# My Essay\n\n![[diagram.png]]")));
        VaultAssetReader availableAssets = VaultAssetReader.createNull(Map.of("diagram.png", imageBytes));
        AtomicInteger resolutions = new AtomicInteger();
        VaultAssetReader intermittentlyFailingAssetReader = reference -> {
            if (resolutions.incrementAndGet() == 1) {
                return availableAssets.resolve(reference);
            }
            throw new UncheckedIOException(new IOException("asset freshness lookup unavailable"));
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(resolvedBody, fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, intermittentlyFailingAssetReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("assets", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("asset freshness lookup unavailable"));
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void preparingALinkedEssayInstallsANonEmptyOccurrenceMap() {
        String target = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: target-essay
                id: src-target-1
                title: Target Essay
                description: A target.
                ---
                Target body.""";
        String referrer = essayWithBody("See [[target-essay]].");
        VaultRelativePath referrerPath = VaultRelativePath.of("blog/referrer.md");
        VaultRelativePath targetPath = VaultRelativePath.of("blog/target-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(referrerPath, referrer, targetPath, target));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "As he wrote, see [Target Essay](/essays/target-essay/).",
                        fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);
        List<Occurrence> occurrences = installed.referenceMap().occurrences();
        assertEquals(1, occurrences.size());
        assertEquals(0, occurrences.get(0).order());
        assertEquals("src-target-1", occurrences.get(0).targetSourceId());
        assertEquals("target-essay", occurrences.get(0).ruLabel());
        assertEquals("Target Essay", occurrences.get(0).enLabel());
    }

    @Test
    void reprepareReusesThePriorOccurrenceIdWhenNothingChanged() {
        String target = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: target-essay
                id: src-target-2
                title: Target Essay
                description: A target.
                ---
                Target body.""";
        String referrer = essayWithBody("See [[target-essay]].");
        VaultRelativePath referrerPath = VaultRelativePath.of("blog/referrer.md");
        VaultRelativePath targetPath = VaultRelativePath.of("blog/target-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(referrerPath, referrer, targetPath, target));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "As he wrote, see [Target Essay](/essays/target-essay/).",
                        fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());
        String firstOccurrenceId = workspace.installed().get(0).referenceMap().occurrences().get(0).id();

        handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());
        String secondOccurrenceId = workspace.installed().get(1).referenceMap().occurrences().get(0).id();

        assertEquals(firstOccurrenceId, secondOccurrenceId);
    }

    @Test
    void prepareBlocksWhenTranslationInventsOrDropsAnOccurrence() {
        String target = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: target-essay
                id: src-target-3
                title: Target Essay
                description: A target.
                ---
                Target body.""";
        String referrer = essayWithBody("See [[target-essay]].");
        VaultRelativePath referrerPath = VaultRelativePath.of("blog/referrer.md");
        VaultRelativePath targetPath = VaultRelativePath.of("blog/target-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(referrerPath, referrer, targetPath, target));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "As he wrote, nothing here at all.",
                        fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void validEssayInstallsOneCandidateAndReturnsReadyForReview() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals("my-essay", response.identity().publicId());
        assertEquals(0, response.diagnostics().size());
        assertEquals(1, workspace.installed().size());
        NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);
        assertEquals("# My Essay\n\nPlain prose body.", installed.ruBody());
        assertEquals("Translated body", installed.enBody());
        assertEquals("Translated title", dev.eugene.publicationexporter.reference.PublicField.value(installed.enFields(), "title").orElseThrow());
        assertEquals("Translated description.", dev.eugene.publicationexporter.reference.PublicField.value(installed.enFields(), "description").orElseThrow());
        assertEquals("My Essay", dev.eugene.publicationexporter.reference.PublicField.value(installed.ruFields(), "title").orElseThrow());
        assertEquals("A valid description.", dev.eugene.publicationexporter.reference.PublicField.value(installed.ruFields(), "description").orElseThrow());
        assertEquals(ContentHash.sha256Hex("# My Essay\n\nPlain prose body."),
                installed.referenceMap().ruHash());
        assertEquals(ContentHash.sha256Hex("Translated body"), installed.referenceMap().enHash());
        assertEquals(ContentHash.sha256Hex(
                dev.eugene.publicationexporter.reference.PublicFieldsCodec.write(
                        fields("My Essay", "A valid description."))), installed.referenceMap().ruFieldsHash());
        assertEquals(ContentHash.sha256Hex(
                dev.eugene.publicationexporter.reference.PublicFieldsCodec.write(
                        fields("Translated title", "Translated description."))), installed.referenceMap().enFieldsHash());
        assertEquals(ContentHash.sha256Hex(""), installed.referenceMap().structuredDataHash());
        assertTrue(installed.referenceMap().occurrences().isEmpty());
    }

    @Test
    void unrelatedInvalidPublicationIsNotTouchedByPreparingTheValidOne() {
        VaultRelativePath validPath = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath invalidPath = VaultRelativePath.of("blog/broken.md");
        VaultReader validNoteReader = VaultReader.createNull(Map.of(validPath, VALID_ESSAY));
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                if (notePath.equals(invalidPath)) {
                    fail("Prepare must not query the unrelated invalid note.");
                }
                return validNoteReader.exists(notePath);
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                if (notePath.equals(invalidPath)) {
                    fail("Prepare must not read the unrelated invalid note.");
                }
                return validNoteReader.readSource(notePath);
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(validPath, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals(1, workspace.installed().size());
        assertEquals("my-essay", workspace.installed().get(0).identity().publicId());
    }

    @Test
    void translationFailureInstallsNoCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNullFailing("worker crashed"), workspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals(1, response.diagnostics().size());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void blankTranslationOutputInstallsNoCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(" \n\t", fields("Translated title", "Translated description.")), workspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void blankTranslatedTitleInstallsNoCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields(" \n\t", "Translated description.")), workspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void blankTranslatedDescriptionInstallsNoCandidate() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", " \n\t")), workspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void unadmittedNoteIsBlockedBeforeReachingTheWorker() {
        String essayWithoutSourceId = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essayWithoutSourceId));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", fields("EN title", "EN description.")));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker, workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("id", response.diagnostics().get(0).field());
        assertTrue(worker.requested().isEmpty());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void workerReceivesTheFrontmatterStrippedBodyPlusTitleAndDescription() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", fields("EN title", "EN description.")));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),worker, CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertEquals(1, worker.requested().size());
        assertEquals("# My Essay\n\nPlain prose body.", worker.requested().get(0).ruBody());
        assertEquals("My Essay", dev.eugene.publicationexporter.reference.PublicField.value(worker.requested().get(0).ruFields(), "title").orElseThrow());
        assertEquals("A valid description.", dev.eugene.publicationexporter.reference.PublicField.value(worker.requested().get(0).ruFields(), "description").orElseThrow());
    }

    @Test
    void obsidianCommentIsStrippedFromInstalledCandidate() {
        String essay = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                Public prose.

                %% private note to self %%

                More public prose.""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals(1, workspace.installed().size());
        String installedRuBody = workspace.installed().get(0).ruBody();
        assertFalse(installedRuBody.contains("%%"));
        assertFalse(installedRuBody.contains("private note to self"));
        assertEquals("# My Essay\n\nPublic prose.\n\n\n\nMore public prose.", installedRuBody);
    }

    @Test
    void linkLikeTextInsideInlineCodeSurvivesNormalization() {
        String essay = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                Example syntax: `[[Some Note]]` is a wiki-link.""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("# My Essay\n\nExample syntax: `[[Some Note]]` is a wiki-link.",
                workspace.installed().get(0).ruBody());
    }

    @Test
    void commentAndLinkLikeTextInsideFencedCodeSurviveNormalization() {
        String essay = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                Public prose.

                ```markdown
                %% this looks like a comment but is inside a fence %%
                [[This looks like a link]]
                ```

                More public prose.""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("Translated body", fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("# My Essay\n\nPublic prose.\n\n```markdown\n"
                + "%% this looks like a comment but is inside a fence %%\n"
                + "[[This looks like a link]]\n```\n\nMore public prose.",
                workspace.installed().get(0).ruBody());
    }

    @Test
    void unclosedObsidianCommentBlocksPreparationWithoutInstallingACandidate() {
        String essay = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                Public prose.

                %% this comment is never closed

                This text is lost if we don't block.""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, essay));
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", fields("EN title", "EN description.")));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker, workspace, ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("never closed"));
        assertTrue(worker.requested().isEmpty());
        assertTrue(workspace.installed().isEmpty());
        assertEquals(null, editor.currentValue(path, "workflowStatus"));
    }

    @Test
    void commentOnlyEditStillCountsAsUnchangedAgainstApprovedBaseline() throws Exception {
        Path reviewRoot = temporaryRoot.resolve("comment-only-edit-review");
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ApprovedSnapshotWorkspace.create(reviewRoot).install(
                identity, "# My Essay\n\nPublic prose.\n\n\n\nMore prose.", "EN body",
                "My Essay", "EN title", "A valid description.", "EN description",
                ReferenceMap.empty(identity,
                        ContentHash.sha256Hex("# My Essay\n\nPublic prose.\n\n\n\nMore prose."),
                        ContentHash.sha256Hex("EN body"),
                        ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("A valid description."), ContentHash.sha256Hex("EN description")));
        String essayWithComment = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                Public prose.

                %% this note was added after approval but changes nothing public %%

                More prose.""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewRoot);
        TranslationWorker refusingWorker = (job, ruBody, ruFields) ->
                fail("Prepare must not invoke the translation worker for a comment-only edit.");
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                refusingWorker, candidateWorkspace, ApprovedSnapshotWorkspace.create(reviewRoot),
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(
                path, VaultReader.createNull(Map.of(path, essayWithComment)), VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        CandidateSnapshot candidate = candidateWorkspace.read(identity).orElseThrow();
        assertEquals("# My Essay\n\nPublic prose.\n\n\n\nMore prose.", candidate.ruBody());
    }

    @Test
    void structuredMetadataOnlyEditAgainstApprovedClaimCreatesReviewCandidate() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "claim", "latency-budget-is-fiction");
        String body = "Claim body.";
        String englishBody = "English claim body.";
        List<PublicField> russianFields = claimFields(
                "A fixed latency budget is fiction",
                "A valid description.",
                "A fixed latency budget is usually the wrong abstraction.");
        List<PublicField> englishFields = claimFields(
                "A fixed latency budget is fiction",
                "A valid English description.",
                "A fixed latency budget is usually the wrong abstraction.");
        String approvedStructuredData = """
                supports:
                  - label: "Old supporting claim"
                sources:
                  - link:
                      label: Old source
                """;
        String currentStructuredData = """
                supports:
                  - label: "New supporting claim"
                sources:
                  - link:
                      label: New source
                """;
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        approved.install(identity, CandidateSnapshot.of(
                body, englishBody, russianFields, englishFields, approvedStructuredData,
                referenceMap(identity, body, englishBody, russianFields, englishFields, approvedStructuredData)));
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success(englishBody, englishFields));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        VaultRelativePath path = VaultRelativePath.of("blog/latency-budget-is-fiction.md");
        String claim = claimWithStructuredData("""
                supports:
                  - label: New supporting claim
                sources:
                  - link:
                      label: New source
                """);
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()), worker, workspace, approved,
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(
                path, VaultReader.createNull(Map.of(path, claim)), VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals(1, worker.requested().size(),
                "a structured metadata edit must take the translation/review path, not mirror approval");
        assertEquals(currentStructuredData, workspace.installed().get(0).structuredData());
        assertEquals(
                ContentHash.sha256Hex(currentStructuredData),
                workspace.installed().get(0).referenceMap().structuredDataHash());
    }

    @Test
    void sameInputsBuiltTwiceProduceIdenticalCandidateBytes() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace firstWorkspace = new NullCandidateWorkspace();
        NullCandidateWorkspace secondWorkspace = new NullCandidateWorkspace();

        new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),TranslationWorker.createNull(
                "Translated body", fields("Translated title", "Translated description.")), firstWorkspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull())
                .prepare(path, vaultReader, VaultAssetReader.createNull());
        new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),TranslationWorker.createNull(
                "Translated body", fields("Translated title", "Translated description.")), secondWorkspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull())
                .prepare(path, vaultReader, VaultAssetReader.createNull());

        NullCandidateWorkspace.InstalledCandidate first = firstWorkspace.installed().get(0);
        NullCandidateWorkspace.InstalledCandidate second = secondWorkspace.installed().get(0);
        assertEquals(first.ruBody(), second.ruBody());
        assertEquals(first.enBody(), second.enBody());
        assertEquals(first.ruFields(), second.ruFields());
        assertEquals(first.enFields(), second.enFields());
        assertEquals(first.structuredData(), second.structuredData());
        assertEquals(first.referenceMap(), second.referenceMap());
        assertEquals(first.referenceMap().ruHash(), second.referenceMap().ruHash());
        assertEquals(first.referenceMap().enHash(), second.referenceMap().enHash());
    }

    @Test
    void unsafePathIsBlockedWithEscapeDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("EN", fields("EN title", "EN description.")), CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(
                VaultRelativePath.of("../../etc/passwd.md"), vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Note path escapes the vault root.", response.diagnostics().get(0).message());
    }

    @Test
    void absentNoteIsBlockedWithNotFoundDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("EN", fields("EN title", "EN description.")), CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(
                VaultRelativePath.of("blog/does-not-exist.md"), vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Note was not found in the vault.", response.diagnostics().get(0).message());
    }

    @Test
    void nonMarkdownPathIsBlockedBeforeReading() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.txt");
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return fail("Prepare must not read a non-Markdown path.");
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("EN", fields("EN title", "EN description.")), CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("Note path must name a Markdown file.", response.diagnostics().get(0).message());
    }

    @Test
    void vanishedNoteDuringReadIsReportedAsMissing() {
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("EN", fields("EN title", "EN description.")), CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");

        BridgeResponse response = handler.prepare(path,
                failingReader(new NoSuchElementException("gone")), VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Note was not found in the vault.", response.diagnostics().get(0).message());
    }

    @Test
    void unreadableNoteDuringReadIsReportedAsMissing() {
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("EN", fields("EN title", "EN description.")), CandidateWorkspace.createNull(),
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");

        BridgeResponse response = handler.prepare(path,
                failingReader(new UncheckedIOException(new IOException("unreadable"))), VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("Note was not found in the vault.", response.diagnostics().get(0).message());
    }

    @Test
    void translationWorkerIoFailureReturnsTranslationFailed() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        TranslationWorker failingWorker = (job, ruBody, ruFields) -> {
            throw new UncheckedIOException(new IOException("scratch directory unavailable"));
        };
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                failingWorker, workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertTrue(response.diagnostics().get(0).message().contains("scratch directory unavailable"));
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void competingPreparationsInstallOnlyTheFreshSource() throws Exception {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        AtomicReference<String> source = new AtomicReference<>(essayWithBody("First source body."));
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                return source.get();
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
        CountDownLatch firstTranslationStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstTranslation = new CountDownLatch(1);
        CountDownLatch releaseSecondTranslation = new CountDownLatch(1);
        AtomicInteger invocation = new AtomicInteger();
        java.util.List<TranslationJob> jobs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        TranslationWorker controlledWorker = (job, ruBody, ruFields) -> {
            jobs.add(job);
            int currentInvocation = invocation.incrementAndGet();
            if (currentInvocation == 1) {
                firstTranslationStarted.countDown();
                await(releaseFirstTranslation);
                return TranslationOutcome.success("Stale English", fields("Stale title", "Stale description"));
            }
            await(releaseSecondTranslation);
            return TranslationOutcome.success("Fresh English", fields("Fresh title", "Fresh description"));
        };
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler firstHandler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                controlledWorker, workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());
        PrepareHandler secondHandler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                controlledWorker, workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BridgeResponse> stalePreparation = executor.submit(
                    () -> firstHandler.prepare(path, vaultReader, VaultAssetReader.createNull()));
            assertTrue(firstTranslationStarted.await(5, TimeUnit.SECONDS));
            source.set(essayWithBody("Second source body."));
            Future<BridgeResponse> freshPreparation = executor.submit(
                    () -> secondHandler.prepare(path, vaultReader, VaultAssetReader.createNull()));

            releaseFirstTranslation.countDown();
            releaseSecondTranslation.countDown();

            assertEquals("stale", stalePreparation.get(5, TimeUnit.SECONDS).status());
            assertEquals("ready_for_review", freshPreparation.get(5, TimeUnit.SECONDS).status());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, jobs.size());
        assertFalse(jobs.get(0).sourceFingerprint().equals(jobs.get(1).sourceFingerprint()));
        assertEquals(1, workspace.installed().size());
        assertEquals("Second source body.", workspace.installed().get(0).ruBody());
        assertEquals("Fresh English", workspace.installed().get(0).enBody());
    }

    @Test
    void candidateInstallIoFailureReturnsTranslationFailed() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        CandidateWorkspace failingWorkspace = new CandidateWorkspace() {
            @Override
            public void install(PublicationIdentity identity, CandidateSnapshot content,
                    List<dev.eugene.publicationexporter.candidate.CandidateAsset> assets) {
                install(identity, content.ruBody(), content.enBody(),
                        dev.eugene.publicationexporter.reference.PublicField.value(content.ruFields(), "title").orElseThrow(),
                        dev.eugene.publicationexporter.reference.PublicField.value(content.enFields(), "title").orElseThrow(),
                        dev.eugene.publicationexporter.reference.PublicField.value(content.ruFields(), "description").orElseThrow(),
                        dev.eugene.publicationexporter.reference.PublicField.value(content.enFields(), "description").orElseThrow(),
                        content.referenceMap());
            }

            @Override
            public void install(PublicationIdentity identity, String ruBody, String enBody,
                    String ruTitle, String enTitle, String ruDescription, String enDescription,
                    ReferenceMap referenceMap) {
                throw new UncheckedIOException(new IOException("candidate disk unavailable"));
            }

            @Override
            public Optional<CandidatePaths> find(PublicationIdentity identity) {
                return Optional.empty();
            }

            @Override
            public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
                return Optional.empty();
            }
        };
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "Translated body", fields("Translated title", "Translated description.")), failingWorkspace,
                ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertTrue(response.diagnostics().get(0).message().contains("candidate disk unavailable"));
    }

    @Test
    void candidateConfinementFailureReturnsTranslationFailedWithoutExternalWrite() throws Exception {
        Path reviewRoot = temporaryRoot.resolve("review");
        Path outsideRoot = temporaryRoot.resolve("outside");
        Files.createDirectories(reviewRoot);
        Files.createDirectories(outsideRoot);
        Files.createSymbolicLink(reviewRoot.resolve("blog"), outsideRoot);
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "Translated body", fields("Translated title", "Translated description.")),
                CandidateWorkspace.create(reviewRoot), ApprovedSnapshotWorkspace.createNull(),
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertTrue(response.diagnostics().get(0).message().contains("escapes review root"));
        assertTrue(Files.notExists(outsideRoot.resolve("my-essay/candidate")));
    }

    @Test
    void corruptApprovedSnapshotReturnsStructuredBlockedResponse() throws Exception {
        Path reviewRoot = temporaryRoot.resolve("corrupt-review");
        installApproved(reviewRoot);
        Files.writeString(reviewRoot.resolve("blog/my-essay/approved/references.json"), "not-json");
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("EN", fields("EN title", "EN description")),
                CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.create(reviewRoot),
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(
                VaultRelativePath.of("blog/my-essay.md"),
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/my-essay.md"), VALID_ESSAY)),
                VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("approved-snapshot", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("Approved snapshot lookup failed"));
    }

    @Test
    void escapingApprovedMemberReturnsStructuredBlockedResponse() throws Exception {
        Path reviewRoot = temporaryRoot.resolve("escaping-review");
        installApproved(reviewRoot);
        Path outside = Files.writeString(temporaryRoot.resolve("outside-ru.md"), "outside");
        Path ruPath = reviewRoot.resolve("blog/my-essay/approved/ru.md");
        Files.delete(ruPath);
        Files.createSymbolicLink(ruPath, outside);
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("EN", fields("EN title", "EN description")),
                CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.create(reviewRoot),
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(
                VaultRelativePath.of("blog/my-essay.md"),
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/my-essay.md"), VALID_ESSAY)),
                VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("approved-snapshot", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("escapes review root"));
    }

    @Test
    void approvedSourceUnchangedButCandidateMissingInstallsCandidateFromApproved() throws Exception {
        Path reviewRoot = temporaryRoot.resolve("self-heal-review");
        installApproved(reviewRoot);
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewRoot);
        TranslationWorker refusingWorker = (job, ruBody, ruFields) ->
                fail("Prepare must not invoke the translation worker when the approved RU baseline is unchanged.");
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                refusingWorker, candidateWorkspace, ApprovedSnapshotWorkspace.create(reviewRoot),
                WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(
                path, VaultReader.createNull(Map.of(path, VALID_ESSAY)), VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertTrue(candidateWorkspace.find(PublicationIdentity.of("blog", "essay", "my-essay")).isPresent(),
                "prepare must not report ready_for_review without a candidate the plugin can actually open");
        CandidateSnapshot candidate = candidateWorkspace.read(PublicationIdentity.of("blog", "essay", "my-essay"))
                .orElseThrow();
        assertEquals("EN body", candidate.enBody());
    }

    @Test
    void approvedAssetBearingSourceRecreatesCandidateWithFreshlyResolvedAssets() {
        byte[] imageBytes = "approved-image".getBytes(StandardCharsets.UTF_8);
        String digest = ContentHash.sha256Hex(imageBytes);
        String assetReference = "/assets/vault/" + digest + ".png";
        String resolvedBody = "# My Essay\n\n![diagram](" + assetReference + ")";
        String englishBody = "# My Essay\n\n![diagram](" + assetReference + ")";
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ReferenceMap referenceMap = ReferenceMap.empty(
                identity, ContentHash.sha256Hex(resolvedBody), ContentHash.sha256Hex(englishBody),
                ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("EN title"),
                ContentHash.sha256Hex("A valid description."), ContentHash.sha256Hex("EN description"));
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        approved.install(identity, resolvedBody, englishBody, "My Essay", "EN title",
                "A valid description.", "EN description", referenceMap);
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        TranslationWorker refusingWorker = (job, ruBody, ruFields) ->
                fail("Prepare must not translate a source that matches its approved baseline.");
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                refusingWorker, workspace, approved, WorkflowStatusEditor.createNull());
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");

        BridgeResponse response = handler.prepare(
                path,
                VaultReader.createNull(Map.of(path, essayWithBody("# My Essay\n\n![[diagram.png]]"))),
                VaultAssetReader.createNull(Map.of("diagram.png", imageBytes)));

        assertTrue(response.ok());
        assertEquals(1, workspace.installed().size());
        assertEquals(1, workspace.installed().get(0).assets().size());
        assertEquals(digest + ".png", workspace.installed().get(0).assets().get(0).publicName());
        assertArrayEquals(imageBytes, workspace.installed().get(0).assets().get(0).content());
    }

    @Test
    void publicLinkResolvesToRouteWhilePrivateLinkBecomesASafeLabel() {
        String publicTarget = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: notes-on-time
                id: 91aa-notes-on-time
                title: Заметка о времени
                description: A valid description.
                ---
                # Заметка о времени

                Public prose.""";
        String privateTarget = """
                ---
                publish: false
                publicCollection: blog
                publicContentType: essay
                publicId: draft
                id: 4c1b-draft
                title: Черновик
                description: A valid description.
                ---
                # Черновик

                Not yet public.""";
        String referrer = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                Смотрите также [[Заметка о времени]] и [[Черновик]].""";
        VaultRelativePath referrerPath = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath publicTargetPath = VaultRelativePath.of("blog/Заметка о времени.md");
        VaultRelativePath privateTargetPath = VaultRelativePath.of("blog/Черновик.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                referrerPath, referrer,
                publicTargetPath, publicTarget,
                privateTargetPath, privateTarget));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "See [Time note](/essays/notes-on-time/) and Draft.",
                        fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals(
                "# My Essay\n\nСмотрите также [Заметка о времени](/essays/notes-on-time/) и Черновик.",
                workspace.installed().get(0).ruBody());
    }

    @Test
    void publicLinkToBlogNoteResolvesToTheNoteRoute() {
        String note = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: note
                publicId: my-note
                id: 91aa-my-note
                title: My Note
                description: A valid description.
                ---
                # My Note

                Public note prose.""";
        String essay = essayWithBody("See [[my-note]].");
        VaultRelativePath essayPath = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath notePath = VaultRelativePath.of("blog/my-note.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                essayPath, essay,
                notePath, note));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "See [My Note](/notes/my-note/).",
                        fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(essayPath, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals("See [my-note](/notes/my-note/).",
                workspace.installed().get(0).ruBody());
    }

    @Test
    void privateTransclusionBlocksPreparationWithoutInstallingACandidate() {
        String privateTarget = """
                ---
                publish: false
                publicCollection: blog
                publicContentType: essay
                publicId: draft
                id: 4c1b-draft
                title: Черновик
                description: A valid description.
                ---
                # Черновик

                Not yet public.""";
        String referrer = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                ![[Черновик]]""";
        VaultRelativePath referrerPath = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath privateTargetPath = VaultRelativePath.of("blog/Черновик.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                referrerPath, referrer, privateTargetPath, privateTarget));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(referrerPath, referrer));
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", fields("EN title", "EN description.")));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker, workspace, ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(worker.requested().isEmpty());
        assertTrue(workspace.installed().isEmpty());
        assertEquals(null, editor.currentValue(referrerPath, "workflowStatus"));
    }

    @Test
    void prepareBlocksWhenADirectPrivateTargetHasNoSourceId() {
        String privateTargetWithoutId = """
                ---
                publish: false
                publicCollection: blog
                publicContentType: essay
                publicId: draft
                title: Черновик
                description: A valid description.
                ---
                # Черновик

                Not yet public.""";
        String referrer = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                Смотрите также [[Черновик]].""";
        VaultRelativePath referrerPath = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath privateTargetPath = VaultRelativePath.of("blog/Черновик.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                referrerPath, referrer, privateTargetPath, privateTargetWithoutId));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", fields("EN title", "EN description.")));
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(referrerPath, referrer));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()), worker, workspace,
                ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertTrue(worker.requested().isEmpty());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void prepareBlocksWhenADirectPrivateTargetSharesTheSourcesOwnSourceId() {
        String referrer = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                Смотрите также [[Черновик]].""";
        String privateTargetWithDuplicateId = """
                ---
                publish: false
                publicCollection: blog
                publicContentType: essay
                publicId: draft
                id: 8f2c-my-essay
                title: Черновик
                description: A valid description.
                ---
                # Черновик

                Not yet public.""";
        VaultRelativePath referrerPath = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath privateTargetPath = VaultRelativePath.of("blog/Черновик.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                referrerPath, referrer, privateTargetPath, privateTargetWithDuplicateId));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", fields("EN title", "EN description.")));
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(referrerPath, referrer));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()), worker, workspace,
                ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertTrue(worker.requested().isEmpty());
        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void prepareSucceedsWhenDirectPrivateTargetsHaveUniqueSourceIds() {
        String referrer = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                Смотрите также [[Черновик]].""";
        String privateTarget = """
                ---
                publish: false
                publicCollection: blog
                publicContentType: essay
                publicId: draft
                id: 4c1b-draft
                title: Черновик
                description: A valid description.
                ---
                # Черновик

                Not yet public.""";
        VaultRelativePath referrerPath = VaultRelativePath.of("blog/my-essay.md");
        VaultRelativePath privateTargetPath = VaultRelativePath.of("blog/Черновик.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                referrerPath, referrer, privateTargetPath, privateTarget));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull("See Draft.", fields("EN title", "EN description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

        assertTrue(response.ok());
        assertEquals(1, workspace.installed().size());
    }

    @Test
    void blockedTransclusionDiagnosticUsesOnlyTheLastPathSegment() {
        String referrer = essayWithBody("![[private-area/Черновик]]");
        VaultRelativePath referrerPath = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(referrerPath, referrer));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", fields("EN title", "EN description.")));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker, workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(referrerPath, vaultReader, VaultAssetReader.createNull());

        assertEquals("Transclusion target \"Черновик\" is not a public note.",
                response.diagnostics().get(0).message());
        assertFalse(response.diagnostics().get(0).message().contains("private-area/"));
    }

    @Test
    void assetEmbedIsResolvedAfterLinkResolution() {
        String essay = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                ![[diagram.png]]

                More prose.""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
        byte[] imageBytes = "pretend-png-bytes".getBytes(StandardCharsets.UTF_8);
        String expectedDigest = ContentHash.sha256Hex(imageBytes);
        String expectedReference = "/assets/vault/" + expectedDigest + ".png";
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of("diagram.png", imageBytes));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "# My Essay\n\n![diagram](" + expectedReference + ")\n\nMore prose.", fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, vaultAssetReader);

        assertTrue(response.ok());
        assertEquals("# My Essay\n\n![diagram](" + expectedReference + ")\n\nMore prose.",
                workspace.installed().get(0).ruBody());
    }

    @Test
    void assetEmbedWithAnExactVaultRelativeMatchResolvesToAContentAddressedReference() {
        byte[] imageBytes = "pretend-png-bytes".getBytes(StandardCharsets.UTF_8);
        String essay = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                ![[diagram.png]]

                More prose.""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of(
                "diagram.png", imageBytes,
                "other/diagram.png", "different-bytes".getBytes(StandardCharsets.UTF_8)));
        String expectedDigest = ContentHash.sha256Hex(imageBytes);
        String expectedReference = "/assets/vault/" + expectedDigest + ".png";
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "# My Essay\n\n![diagram](" + expectedReference + ")\n\nMore prose.", fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, vaultAssetReader);

        assertTrue(response.ok());
        assertEquals(
                "# My Essay\n\n![diagram](" + expectedReference + ")\n\nMore prose.",
                workspace.installed().get(0).ruBody());
        assertEquals(1, workspace.installed().get(0).assets().size());
        assertEquals(expectedDigest + ".png", workspace.installed().get(0).assets().get(0).publicName());
        assertArrayEquals(imageBytes, workspace.installed().get(0).assets().get(0).content());
    }

    @Test
    void ambiguousAssetBasenameBlocksPreparationWithoutInstallingACandidate() {
        String essay = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                ![[logo.png]]""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of(
                "assets/logo.png", "a".getBytes(StandardCharsets.UTF_8),
                "archive/logo.png", "b".getBytes(StandardCharsets.UTF_8)));
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, essay));
        NullTranslationWorker worker = new NullTranslationWorker(
                TranslationOutcome.success("EN", fields("EN title", "EN description.")));
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                worker, workspace, ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.prepare(path, vaultReader, vaultAssetReader);

        assertFalse(response.ok());
        assertEquals("translation_failed", response.status());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(worker.requested().isEmpty());
        assertTrue(workspace.installed().isEmpty());
        assertEquals(null, editor.currentValue(path, "workflowStatus"));
    }

    @Test
    void identicalAssetBytesReferencedTwiceMaterializeAsOnePublicAsset() {
        byte[] sharedBytes = "same-bytes".getBytes(StandardCharsets.UTF_8);
        String essay = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                # My Essay

                ![[a/cover.png]] and ![[b/cover.png]]""";
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull(Map.of(
                "a/cover.png", sharedBytes, "b/cover.png", sharedBytes));
        String expectedDigest = ContentHash.sha256Hex(sharedBytes);
        String expectedReference = "/assets/vault/" + expectedDigest + ".png";
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PrepareHandler handler = new PrepareHandler(
                new NoteIntake(PublicationKinds.installed()),
                TranslationWorker.createNull(
                        "# My Essay\n\n![cover](" + expectedReference + ") and ![cover](" + expectedReference + ")", fields("Translated title", "Translated description.")),
                workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

        BridgeResponse response = handler.prepare(path, vaultReader, vaultAssetReader);

        assertTrue(response.ok());
        assertEquals(
                "# My Essay\n\n![cover](" + expectedReference + ") and ![cover](" + expectedReference + ")",
                workspace.installed().get(0).ruBody());
        assertEquals(1, workspace.installed().get(0).assets().size());
        assertEquals(expectedDigest + ".png", workspace.installed().get(0).assets().get(0).publicName());
        assertArrayEquals(sharedBytes, workspace.installed().get(0).assets().get(0).content());
    }

    private VaultReader failingReader(RuntimeException failure) {
        return new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath notePath) {
                return true;
            }

            @Override
            public String readSource(VaultRelativePath notePath) {
                throw failure;
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for controlled translation completion");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Controlled translation was interrupted", interrupted);
        }
    }

    private static String essayWithBody(String body) {
        return """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                title: My Essay
                description: A valid description.
                ---
                """ + body;
    }

    private static String bookWithTranslatedField(String field, String value) {
        return """
                ---
                publish: true
                publicCollection: bibliography
                publicContentType: book
                publicId: the-lean-startup
                id: 8f2c-the-lean-startup
                title: The Lean Startup
                description: A valid description.
                authors:
                  - Eric Ries
                """ + field + ": " + value + """

                ---
                # The Lean Startup

                A reading note body.""";
    }

    private static List<dev.eugene.publicationexporter.reference.PublicField> fields(
            String title, String description) {
        return List.of(
                dev.eugene.publicationexporter.reference.PublicField.of("title", title),
                dev.eugene.publicationexporter.reference.PublicField.of("description", description));
    }

    private static List<PublicField> bookFields(
            String title, String description, String use, String boundary) {
        return List.of(
                PublicField.of("title", title),
                PublicField.of("description", description),
                PublicField.of("use", use),
                PublicField.of("boundary", boundary));
    }

    private static List<PublicField> albumFields(
            String title, String description, String context, String association) {
        return List.of(
                PublicField.of("title", title),
                PublicField.of("description", description),
                PublicField.of("context", context),
                PublicField.of("association", association),
                PublicField.of("format", "LP"),
                PublicField.of("care", "Listen with headphones."),
                PublicField.of("listenFor[0]", "modal harmony"),
                PublicField.of("listenFor[1]", "ensemble interaction"));
    }

    private static List<PublicField> curatedPageFields(String title) {
        return List.of(
                PublicField.of("title", title),
                PublicField.of("summary", "Summary."),
                PublicField.of("eyebrow", "Eyebrow."),
                PublicField.of("lead", "Lead."),
                PublicField.of("principles[0].title", "Principle"),
                PublicField.of("principles[0].text", "Principle text."),
                PublicField.of("colophon", "Colophon."));
    }

    private static List<PublicField> claimFields(String title, String description, String statement) {
        return List.of(
                PublicField.of("title", title),
                PublicField.of("description", description),
                PublicField.of("statement", statement));
    }

    private static ReferenceMap referenceMap(
            PublicationIdentity identity, String ruBody, String enBody,
            List<PublicField> ruFields, List<PublicField> enFields, String structuredData) {
        return ReferenceMap.empty(
                identity,
                ContentHash.sha256Hex(ruBody),
                ContentHash.sha256Hex(enBody),
                ContentHash.sha256Hex(PublicFieldsCodec.write(ruFields)),
                ContentHash.sha256Hex(PublicFieldsCodec.write(enFields)),
                ContentHash.sha256Hex(structuredData));
    }

    private static String claimWithStructuredData(String structuredData) {
        return """
                ---
                publish: true
                publicCollection: blog
                publicContentType: claim
                publicId: latency-budget-is-fiction
                id: 91aa-latency-claim
                title: A fixed latency budget is fiction
                description: A valid description.
                statement: A fixed latency budget is usually the wrong abstraction.
                """ + structuredData.stripTrailing() + """

                ---
                Claim body.""";
    }

    private static String bookStructuredData(
            String publication,
            String publicationDate,
            String start,
            String end,
            String readingStatus) {
        StringBuilder yaml = new StringBuilder("""
                authors:
                  - "Eric Ries"
                """);
        appendStructuredLine(yaml, "publication", publication);
        appendStructuredLine(yaml, "publicationDate", publicationDate);
        appendStructuredLine(yaml, "start", start);
        appendStructuredLine(yaml, "end", end);
        appendStructuredLine(yaml, "readingStatus", readingStatus);
        return yaml.toString();
    }

    private static String albumStructuredData(
            String artist, String work, String releaseDate, String streamingUrl,
            String bandcampEmbedUrl, List<String> genreTags) {
        StringBuilder yaml = new StringBuilder();
        appendStructuredLine(yaml, "artist", artist);
        appendStructuredLine(yaml, "work", work);
        appendStructuredLine(yaml, "releaseDate", releaseDate);
        appendStructuredLine(yaml, "streamingUrl", streamingUrl);
        appendStructuredLine(yaml, "bandcampEmbedUrl", bandcampEmbedUrl);
        yaml.append("genreTags:\n");
        for (String genreTag : genreTags) {
            yaml.append("  - \"").append(genreTag).append("\"\n");
        }
        yaml.append("reviewType: \"album\"\n");
        return yaml.toString();
    }

    private static void appendStructuredLine(StringBuilder yaml, String key, String value) {
        if (value != null) {
            yaml.append(key).append(": \"").append(value).append("\"\n");
        }
    }

    private void installApproved(Path reviewRoot) {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ApprovedSnapshotWorkspace.create(reviewRoot).install(
                identity, "# My Essay\n\nPlain prose body.", "EN body",
                "My Essay", "EN title", "A valid description.", "EN description",
                ReferenceMap.empty(identity,
                        ContentHash.sha256Hex("# My Essay\n\nPlain prose body."),
                        ContentHash.sha256Hex("EN body"),
                        ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("A valid description."),
                        ContentHash.sha256Hex("EN description")));
    }
}
