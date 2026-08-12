package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.intake.NoteIntake;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspaceConfinementException;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectPublicationHandlerTest {

    @TempDir
    Path temporaryRoot;

    private final InspectPublicationHandler handler =
            new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()),
                    CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.createNull());

    @Test
    void unsafePathIsBlockedWithEscapeDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        BridgeResponse response = handler.inspect(
                VaultRelativePath.of("../../etc/passwd.md"), vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals(1, response.diagnostics().size());
        assertEquals("Note path escapes the vault root.",
                response.diagnostics().get(0).message());
    }

    @Test
    void absentNoteIsBlockedWithNotFoundDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        BridgeResponse response = handler.inspect(
                VaultRelativePath.of("blog/does-not-exist.md"), vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals(1, response.diagnostics().size());
        assertEquals("Note was not found in the vault.",
                response.diagnostics().get(0).message());
    }

    @Test
    void nonMarkdownPathIsBlockedBeforeReading() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.txt");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));

        BridgeResponse response = handler.inspect(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("Note path must name a Markdown file.",
                response.diagnostics().get(0).message());
    }

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
            """;

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
            ---
            """;

    @Test
    void validEssayIsAcceptedWithAllStatesAbsent() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));

        BridgeResponse response = handler.inspect(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("not_prepared", response.status());
        assertEquals("blog", response.identity().publicCollection());
        assertEquals("essay", response.identity().publicContentType());
        assertEquals("my-essay", response.identity().publicId());
        assertEquals("absent", response.candidateState());
        assertEquals("absent", response.approvedSnapshotState());
        assertEquals("absent", response.semanticReferenceState());
        assertEquals("absent", response.releaseState());
        assertEquals(0, response.diagnostics().size());
    }

    @Test
    void bibliographyBookIsAcceptedWithAllStatesAbsent() {
        VaultRelativePath path = VaultRelativePath.of("bibliography/the-lean-startup.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_BOOK));

        BridgeResponse response = handler.inspect(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("not_prepared", response.status());
        assertEquals("bibliography", response.identity().publicCollection());
        assertEquals("book", response.identity().publicContentType());
        assertEquals("the-lean-startup", response.identity().publicId());
        assertEquals("absent", response.candidateState());
        assertEquals("absent", response.approvedSnapshotState());
        assertEquals("absent", response.semanticReferenceState());
        assertEquals("absent", response.releaseState());
    }

    @Test
    void bibliographyBookReviewPlanHandlesAddedOptionalTranslatedField() {
        Path reviewRoot = temporaryRoot.resolve("book-added-field-review");
        PublicationIdentity identity = PublicationIdentity.of("bibliography", "book", "the-lean-startup");
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewRoot);
        ApprovedSnapshotWorkspace approvedWorkspace = ApprovedSnapshotWorkspace.create(reviewRoot);
        List<PublicField> approvedRuFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid description."),
                PublicField.of("use", "Explains how to test demand before scaling a product bet."));
        List<PublicField> approvedEnFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid English description."),
                PublicField.of("use", "Explains how to test demand before scaling a product bet."));
        List<PublicField> candidateRuFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid description."),
                PublicField.of("use", "Explains how to test demand before scaling a product bet."),
                PublicField.of("boundary", "Only the startup-method parts are directly relevant."));
        List<PublicField> candidateEnFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid English description."),
                PublicField.of("use", "Explains how to test demand before scaling a product bet."),
                PublicField.of("boundary", "Only the startup-method parts are directly relevant."));
        String structuredData = """
                authors:
                  - "Eric Ries"
                publication: "Crown Business"
                publicationDate: "2011-09-13"
                readingStatus: "finished"
                """;
        approvedWorkspace.install(identity, CandidateSnapshot.of(
                "RU body", "EN body", approvedRuFields, approvedEnFields, structuredData,
                referenceMap(identity, "RU body", "EN body", approvedRuFields, approvedEnFields, structuredData)));
        candidateWorkspace.install(identity, "RU body", "EN body",
                candidateRuFields, candidateEnFields, structuredData,
                referenceMap(identity, "RU body", "EN body", candidateRuFields, candidateEnFields, structuredData));
        InspectPublicationHandler handlerWithBookReview = new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()), candidateWorkspace, approvedWorkspace);

        BridgeResponse response = handlerWithBookReview.inspect(
                VaultRelativePath.of("bibliography/the-lean-startup.md"),
                VaultReader.createNull(Map.of(VaultRelativePath.of("bibliography/the-lean-startup.md"), VALID_BOOK)));

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals("changed", response.reviewPlan().baselineState());
        assertFalse(response.reviewPlan().diff().isEmpty());
        assertTrue(response.reviewPlan().diff().stream()
                .anyMatch(line -> line.text().contains("boundary: Only the startup-method parts are directly relevant.")));
    }

    @Test
    void bibliographyBookReviewPlanHandlesOptionalTranslatedFieldKeySwap() {
        Path reviewRoot = temporaryRoot.resolve("book-key-swap-review");
        PublicationIdentity identity = PublicationIdentity.of("bibliography", "book", "the-lean-startup");
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewRoot);
        ApprovedSnapshotWorkspace approvedWorkspace = ApprovedSnapshotWorkspace.create(reviewRoot);
        List<PublicField> approvedRuFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid description."),
                PublicField.of("use", "Same note"));
        List<PublicField> approvedEnFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid English description."),
                PublicField.of("use", "Same note"));
        List<PublicField> candidateRuFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid description."),
                PublicField.of("boundary", "Same note"));
        List<PublicField> candidateEnFields = List.of(
                PublicField.of("title", "The Lean Startup"),
                PublicField.of("description", "A valid English description."),
                PublicField.of("boundary", "Same note"));
        String structuredData = """
                authors:
                  - "Eric Ries"
                readingStatus: "finished"
                """;
        approvedWorkspace.install(identity, CandidateSnapshot.of(
                "RU body", "EN body", approvedRuFields, approvedEnFields, structuredData,
                referenceMap(identity, "RU body", "EN body", approvedRuFields, approvedEnFields, structuredData)));
        candidateWorkspace.install(identity, "RU body", "EN body",
                candidateRuFields, candidateEnFields, structuredData,
                referenceMap(identity, "RU body", "EN body", candidateRuFields, candidateEnFields, structuredData));
        InspectPublicationHandler handlerWithBookReview = new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()), candidateWorkspace, approvedWorkspace);

        BridgeResponse response = handlerWithBookReview.inspect(
                VaultRelativePath.of("bibliography/the-lean-startup.md"),
                VaultReader.createNull(Map.of(VaultRelativePath.of("bibliography/the-lean-startup.md"), VALID_BOOK)));

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals("changed", response.reviewPlan().baselineState());
        assertFalse(response.reviewPlan().diff().isEmpty());
        assertTrue(response.reviewPlan().diff().stream()
                .anyMatch(line -> line.text().contains("use: Same note")
                        || line.text().contains("boundary: Same note")));
    }

    @Test
    void persistedStaleWithoutCandidateOrApprovedIsReportedByInspect() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        String staleEssay = VALID_ESSAY.replace(
                "description: A valid description.\n",
                "description: A valid description.\nworkflowStatus: stale\n");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, staleEssay));

        BridgeResponse response = handler.inspect(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("stale", response.status());
        assertEquals("absent", response.candidateState());
        assertEquals("absent", response.approvedSnapshotState());
    }

    @Test
    void approvedSnapshotWithNoCandidateReportsReadyToPublishNotNotPrepared() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        approved.install(identity, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", ReferenceMap.empty(identity,
                        ContentHash.sha256Hex("RU body"), ContentHash.sha256Hex("EN body"),
                        ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("RU description."), ContentHash.sha256Hex("EN description.")));
        InspectPublicationHandler handler =
                new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()), CandidateWorkspace.createNull(), approved);
        VaultReader vaultReader = VaultReader.createNull(Map.of(
                VaultRelativePath.of("blog/my-essay.md"), VALID_ESSAY));

        BridgeResponse response = handler.inspect(VaultRelativePath.of("blog/my-essay.md"), vaultReader);

        assertTrue(response.ok());
        assertEquals("ready_to_publish", response.status());
        assertEquals("absent", response.candidateState());
        assertEquals("ready", response.approvedSnapshotState());
    }

    @Test
    void candidateIdenticalToApprovedSnapshotReportsReadyToPublish() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ReferenceMap referenceMap = ReferenceMap.empty(identity,
                ContentHash.sha256Hex("RU body"), ContentHash.sha256Hex("EN body"),
                ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                ContentHash.sha256Hex("RU description."), ContentHash.sha256Hex("EN description."));
        NullCandidateWorkspace candidate = new NullCandidateWorkspace();
        candidate.install(identity, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);
        ApprovedSnapshotWorkspace approved = ApprovedSnapshotWorkspace.createNull();
        approved.install(identity, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);
        InspectPublicationHandler handler = new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()), candidate, approved);

        BridgeResponse response = handler.inspect(VaultRelativePath.of("blog/my-essay.md"),
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/my-essay.md"), VALID_ESSAY)));

        assertTrue(response.ok());
        assertEquals("ready_to_publish", response.status());
        assertEquals("ready", response.candidateState());
        assertEquals("ready", response.approvedSnapshotState());
        assertNull(response.reviewPlan());
    }

    @Test
    void essayMissingSourceIdIsBlocked() {
        String essayWithoutSourceId = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                title: My Essay
                description: A valid description.
                ---
                """;
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essayWithoutSourceId));

        BridgeResponse response = handler.inspect(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("id", response.diagnostics().get(0).field());
    }

    @Test
    void essayWithUnsupportedContentTypeIsBlocked() {
        String unsupportedNote = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: book
                publicId: my-book
                id: 8f2c-my-book
                title: My Essay
                description: A valid description.
                ---
                """;
        VaultRelativePath path = VaultRelativePath.of("blog/my-book.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, unsupportedNote));

        BridgeResponse response = handler.inspect(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("publicContentType", response.diagnostics().get(0).field());
    }

    @Test
    void vanishedNoteDuringReadIsReportedAsMissing() {
        BridgeResponse response = handler.inspect(VaultRelativePath.of("blog/my-essay.md"),
                failingReader(new NoSuchElementException("gone")));

        assertFalse(response.ok());
        assertEquals("Note was not found in the vault.", response.diagnostics().get(0).message());
    }

    @Test
    void unreadableNoteDuringReadIsReportedAsMissing() {
        BridgeResponse response = handler.inspect(VaultRelativePath.of("blog/my-essay.md"),
                failingReader(new UncheckedIOException(new IOException("unreadable"))));

        assertFalse(response.ok());
        assertEquals("Note was not found in the vault.", response.diagnostics().get(0).message());
    }

    @Test
    void essayWithACompleteCandidateReportsReadyWithAFirstPublicationReviewPlan() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        NullCandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        candidateWorkspace.install(identity, "RU body", "EN body",
                "RU title", "EN title", "RU description", "EN description",
                ReferenceMap.empty(identity, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));
        InspectPublicationHandler handlerWithCandidate = new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()),
                candidateWorkspace, ApprovedSnapshotWorkspace.createNull());

        BridgeResponse response = handlerWithCandidate.inspect(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("ready_for_review", response.status());
        assertEquals("ready", response.candidateState());
        assertEquals("absent", response.approvedSnapshotState());
        assertEquals("absent", response.semanticReferenceState());
        assertEquals("absent", response.releaseState());
        assertEquals("absent", response.reviewPlan().baselineState());
        assertEquals(2, response.reviewPlan().targets().size());
        assertEquals("ru", response.reviewPlan().targets().get(0).language());
        assertEquals("en", response.reviewPlan().targets().get(1).language());
        assertNull(response.reviewPlan().targets().get(0).publishedPath());
        assertEquals("RU title", response.reviewPlan().ruTitle());
        assertEquals("EN title", response.reviewPlan().enTitle());
        assertEquals("RU description", response.reviewPlan().ruDescription());
        assertEquals("EN description", response.reviewPlan().enDescription());
    }

    @Test
    void candidateLookupConfinementFailureReturnsBlockedResponse() {
        CandidateWorkspace candidateWorkspace = candidateWorkspaceThrowing(candidateConfinementFailure());
        InspectPublicationHandler handlerWithFailingLookup = new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()),
                candidateWorkspace, ApprovedSnapshotWorkspace.createNull());
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));

        BridgeResponse response = handlerWithFailingLookup.inspect(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals(2, response.schemaVersion());
        assertEquals("inspect-publication", response.command());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("Candidate lookup failed"));
        assertTrue(response.diagnostics().get(0).blocking());
    }

    @Test
    void candidateLookupIoFailureReturnsBlockedResponse() {
        CandidateWorkspace candidateWorkspace = candidateWorkspaceThrowing(
                new UncheckedIOException(new IOException("candidate directory unavailable")));
        InspectPublicationHandler handlerWithFailingLookup = new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()),
                candidateWorkspace, ApprovedSnapshotWorkspace.createNull());
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));

        BridgeResponse response = handlerWithFailingLookup.inspect(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals(2, response.schemaVersion());
        assertEquals("inspect-publication", response.command());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertEquals("Candidate lookup failed: candidate directory unavailable",
                response.diagnostics().get(0).message());
        assertTrue(response.diagnostics().get(0).blocking());
    }

    @Test
    void corruptApprovedSnapshotReturnsStructuredBlockedResponse() throws Exception {
        Path reviewRoot = temporaryRoot.resolve("corrupt-review");
        installCandidateAndApproved(reviewRoot);
        Files.writeString(reviewRoot.resolve("blog/my-essay/approved/references.json"), "not-json");
        InspectPublicationHandler handlerWithCorruptApproved = new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()),
                CandidateWorkspace.create(reviewRoot), ApprovedSnapshotWorkspace.create(reviewRoot));

        BridgeResponse response = handlerWithCorruptApproved.inspect(
                VaultRelativePath.of("blog/my-essay.md"),
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/my-essay.md"), VALID_ESSAY)));

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("approved-snapshot", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("Approved snapshot lookup failed"));
    }

    @Test
    void corruptApprovedSnapshotWithNoCandidateReturnsStructuredBlockedResponse() throws Exception {
        Path reviewRoot = temporaryRoot.resolve("corrupt-approved-only-review");
        installApprovedOnly(reviewRoot);
        Files.writeString(reviewRoot.resolve("blog/my-essay/approved/references.json"), "not-json");
        InspectPublicationHandler handlerWithCorruptApproved = new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()),
                CandidateWorkspace.create(reviewRoot), ApprovedSnapshotWorkspace.create(reviewRoot));

        BridgeResponse response = handlerWithCorruptApproved.inspect(
                VaultRelativePath.of("blog/my-essay.md"),
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/my-essay.md"), VALID_ESSAY)));

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("approved-snapshot", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("Approved snapshot lookup failed"));
    }

    @Test
    void candidateMissingRequiredFieldReturnsStructuredBlockedResponse() throws Exception {
        Path reviewRoot = temporaryRoot.resolve("missing-candidate-field-review");
        installCandidateAndApproved(reviewRoot);
        Path fieldsPath = reviewRoot.resolve("blog/my-essay/candidate/ru.fields.json");
        Files.writeString(fieldsPath, "[{\"key\":\"description\",\"value\":\"Only description\"}]\n");

        InspectPublicationHandler handlerWithCorruptCandidate = new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()),
                CandidateWorkspace.create(reviewRoot), ApprovedSnapshotWorkspace.createNull());
        BridgeResponse response = handlerWithCorruptCandidate.inspect(
                VaultRelativePath.of("blog/my-essay.md"),
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/my-essay.md"), VALID_ESSAY)));

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("candidate", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("missing required field 'title'"));
    }

    @Test
    void escapingApprovedMemberReturnsStructuredBlockedResponse() throws Exception {
        Path reviewRoot = temporaryRoot.resolve("escaping-review");
        installCandidateAndApproved(reviewRoot);
        Path outside = Files.writeString(temporaryRoot.resolve("outside-approved-ru.md"), "outside");
        Path ruPath = reviewRoot.resolve("blog/my-essay/approved/ru.md");
        Files.delete(ruPath);
        Files.createSymbolicLink(ruPath, outside);
        InspectPublicationHandler handlerWithEscapingApproved = new InspectPublicationHandler(
                new NoteIntake(PublicationKinds.installed()),
                CandidateWorkspace.create(reviewRoot), ApprovedSnapshotWorkspace.create(reviewRoot));

        BridgeResponse response = handlerWithEscapingApproved.inspect(
                VaultRelativePath.of("blog/my-essay.md"),
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/my-essay.md"), VALID_ESSAY)));

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("approved-snapshot", response.diagnostics().get(0).field());
        assertTrue(response.diagnostics().get(0).message().contains("escapes review root"));
    }

    private CandidateWorkspace candidateWorkspaceThrowing(RuntimeException failure) {
        return new CandidateWorkspace() {
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
            public void install(
                    PublicationIdentity identity,
                    String ruBody,
                    String enBody,
                    String ruTitle,
                    String enTitle,
                    String ruDescription,
                    String enDescription,
                    ReferenceMap referenceMap) {
                // no-op: this test double exercises only the lookup side
            }

            @Override
            public Optional<CandidatePaths> find(PublicationIdentity identity) {
                throw failure;
            }

            @Override
            public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
                throw failure;
            }
        };
    }

    private CandidateWorkspaceConfinementException candidateConfinementFailure() {
        CandidateWorkspace realWorkspace = CandidateWorkspace.create(Path.of("/review"));
        PublicationIdentity escapingIdentity = PublicationIdentity.of("../..", "essay", "outside");
        try {
            realWorkspace.find(escapingIdentity);
        } catch (CandidateWorkspaceConfinementException failure) {
            return failure;
        }
        throw new AssertionError("Expected an escaping identity to fail candidate-workspace confinement");
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

    private void installCandidateAndApproved(Path reviewRoot) {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ReferenceMap referenceMap = ReferenceMap.empty(
                identity,
                ContentHash.sha256Hex("RU body"), ContentHash.sha256Hex("EN body"),
                ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                ContentHash.sha256Hex("RU description"), ContentHash.sha256Hex("EN description"));
        CandidateWorkspace.create(reviewRoot).install(
                identity, "RU body", "EN body", "RU title", "EN title",
                "RU description", "EN description", referenceMap);
        ApprovedSnapshotWorkspace.create(reviewRoot).install(
                identity, "RU body", "EN body", "RU title", "EN title",
                "RU description", "EN description", referenceMap);
    }

    private void installApprovedOnly(Path reviewRoot) {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
        ReferenceMap referenceMap = ReferenceMap.empty(
                identity,
                ContentHash.sha256Hex("RU body"), ContentHash.sha256Hex("EN body"),
                ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                ContentHash.sha256Hex("RU description"), ContentHash.sha256Hex("EN description"));
        ApprovedSnapshotWorkspace.create(reviewRoot).install(
                identity, "RU body", "EN body", "RU title", "EN title",
                "RU description", "EN description", referenceMap);
    }

    private static ReferenceMap referenceMap(
            PublicationIdentity identity,
            String ruBody,
            String enBody,
            List<PublicField> ruFields,
            List<PublicField> enFields,
            String structuredData) {
        return ReferenceMap.empty(
                identity,
                ContentHash.sha256Hex(ruBody),
                ContentHash.sha256Hex(enBody),
                ContentHash.sha256Hex(PublicFieldsCodec.write(ruFields)),
                ContentHash.sha256Hex(PublicFieldsCodec.write(enFields)),
                ContentHash.sha256Hex(structuredData));
    }
}
