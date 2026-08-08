package dev.eugene.publicationexporter.refresh;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotIntegrityException;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.NullCandidateWorkspace;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.NullWorkflowStatusEditor;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RefreshPublicationQueueHandlerTest {

    private static final VaultRelativePath STALE_SCALAR_NOTE = VaultRelativePath.of("blog/stale-scalar.md");
    private static final VaultRelativePath UP_TO_DATE_NOTE = VaultRelativePath.of("blog/up-to-date.md");
    private static final VaultRelativePath UNCERTAIN_NOTE = VaultRelativePath.of("blog/uncertain.md");
    private static final VaultRelativePath MALFORMED_NOTE = VaultRelativePath.of("blog/malformed.md");
    private static final VaultRelativePath NOT_PUBLISHED_NOTE = VaultRelativePath.of("blog/draft.md");

    @Test
    void refreshCountsUpdatedUnchangedUncertainAndExcludesUnadmittedNotes() {
        String staleScalarSource = essaySource("stale-scalar", "workflowStatus: not_prepared");
        String upToDateSource = essaySource("up-to-date", "workflowStatus: ready_for_review");
        String uncertainSource = essaySource("uncertain", "workflowStatus: ready_for_review");
        String malformedSource = "---\npublish: true\npublicId: malformed\n---\nMissing required fields.";
        String draftSource = "---\npublish: false\npublicId: draft\n---\nNot a candidate.";

        Map<VaultRelativePath, String> notes = new LinkedHashMap<>();
        notes.put(STALE_SCALAR_NOTE, staleScalarSource);
        notes.put(UP_TO_DATE_NOTE, upToDateSource);
        notes.put(UNCERTAIN_NOTE, uncertainSource);
        notes.put(MALFORMED_NOTE, malformedSource);
        notes.put(NOT_PUBLISHED_NOTE, draftSource);
        VaultReader vaultReader = VaultReader.createNull(notes);

        CandidateWorkspace candidateWorkspace = new NullCandidateWorkspace();
        PublicationIdentity upToDateIdentity = PublicationIdentity.of("blog", "essay", "up-to-date");
        candidateWorkspace.install(upToDateIdentity, "RU", "EN", "RU title", "EN title",
                "RU description.", "EN description.", ReferenceMap.empty(upToDateIdentity,
                        ContentHash.sha256Hex("RU"), ContentHash.sha256Hex("EN"),
                        ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("RU description."), ContentHash.sha256Hex("EN description.")));
        // With neither a candidate nor an approved snapshot, stale-scalar would classify as not_prepared
        // and already match its persisted scalar. An approved snapshot makes ready_to_publish the decisive
        // current classification, so the stale not_prepared scalar must be updated.
        PublicationIdentity staleScalarIdentity = PublicationIdentity.of("blog", "essay", "stale-scalar");
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        approvedSnapshotWorkspace.install(staleScalarIdentity, "RU", "EN", "RU title", "EN title",
                "RU description.", "EN description.", ReferenceMap.empty(staleScalarIdentity,
                        ContentHash.sha256Hex("RU"), ContentHash.sha256Hex("EN"),
                        ContentHash.sha256Hex("RU title"), ContentHash.sha256Hex("EN title"),
                        ContentHash.sha256Hex("RU description."), ContentHash.sha256Hex("EN description.")));

        Map<VaultRelativePath, String> editorNotes = new LinkedHashMap<>(notes);
        editorNotes.put(UNCERTAIN_NOTE, uncertainSource + "\nChanged after queue validation.");
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(editorNotes);
        RefreshPublicationQueueHandler handler = new RefreshPublicationQueueHandler(
                candidateWorkspace, approvedSnapshotWorkspace, editor);

        BridgeResponse response = handler.refresh(vaultReader);

        assertEquals("queue_refreshed", response.status());
        assertEquals(1, response.updatedCount());
        assertEquals(1, response.unchangedCount());
        // Admission failure is the observed metadata_blocked outcome, not uncertain classification evidence.
        // The malformed note is therefore excluded from all three queue counts; the draft is not discovered.
        assertEquals(1, response.uncertainCount());
        assertEquals("ready_to_publish", editor.currentValue(STALE_SCALAR_NOTE, "workflowStatus"));
        assertEquals("ready_for_review", editor.currentValue(UNCERTAIN_NOTE, "workflowStatus"));
    }

    @Test
    void refreshUsesOnlyTheSourceSnapshotAdmittedByNoteIntake() {
        VaultRelativePath notePath = VaultRelativePath.of("blog/single-read.md");
        String source = essaySource("single-read", "workflowStatus: ready_for_review");
        AtomicInteger sourceReads = new AtomicInteger();
        VaultReader vaultReader = new VaultReader() {
            @Override
            public boolean exists(VaultRelativePath path) {
                return path.value().equals(notePath.value());
            }

            @Override
            public String readSource(VaultRelativePath path) {
                sourceReads.incrementAndGet();
                return source;
            }

            @Override
            public List<VaultRelativePath> listPublishCandidates() {
                return List.of(notePath);
            }
        };
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(notePath, source));
        RefreshPublicationQueueHandler handler = new RefreshPublicationQueueHandler(
                CandidateWorkspace.createNull(), ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.refresh(vaultReader);

        assertEquals(1, sourceReads.get());
        assertEquals(1, response.updatedCount());
        assertEquals("not_prepared", editor.currentValue(notePath, "workflowStatus"));
    }

    @Test
    void refreshDoesNotTreatIncompleteCandidateAsReadyForReview() {
        VaultRelativePath notePath = VaultRelativePath.of("blog/incomplete-candidate.md");
        String source = essaySource("incomplete-candidate", "workflowStatus: ready_for_review");
        NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(notePath, source));
        RefreshPublicationQueueHandler handler = new RefreshPublicationQueueHandler(
                incompleteCandidateWorkspace(), ApprovedSnapshotWorkspace.createNull(), editor);

        BridgeResponse response = handler.refresh(VaultReader.createNull(Map.of(notePath, source)));

        assertEquals(1, response.updatedCount());
        assertEquals(0, response.unchangedCount());
        assertEquals("not_prepared", editor.currentValue(notePath, "workflowStatus"));
    }

    @Test
    void refreshCountsWorkspaceAndWriteFailuresUncertainAndContinues() {
        VaultRelativePath candidateFailurePath = VaultRelativePath.of("blog/candidate-failure.md");
        VaultRelativePath approvedFailurePath = VaultRelativePath.of("blog/approved-failure.md");
        VaultRelativePath writeFailurePath = VaultRelativePath.of("blog/write-failure.md");
        VaultRelativePath laterSuccessPath = VaultRelativePath.of("blog/later-success.md");
        Map<VaultRelativePath, String> notes = new LinkedHashMap<>();
        notes.put(candidateFailurePath,
                essaySource("candidate-failure", "workflowStatus: ready_for_review"));
        notes.put(approvedFailurePath,
                essaySource("approved-failure", "workflowStatus: ready_for_review"));
        notes.put(writeFailurePath,
                essaySource("write-failure", "workflowStatus: ready_for_review"));
        notes.put(laterSuccessPath,
                essaySource("later-success", "workflowStatus: ready_for_review"));
        NullWorkflowStatusEditor delegateEditor = new NullWorkflowStatusEditor(notes);
        WorkflowStatusEditor failingEditor = (notePath, expectedSourceHash, newValue) -> {
            if (notePath.value().equals(writeFailurePath.value())) {
                throw new UncheckedIOException(new IOException("workflow note unavailable"));
            }
            return delegateEditor.write(notePath, expectedSourceHash, newValue);
        };
        RefreshPublicationQueueHandler handler = new RefreshPublicationQueueHandler(
                candidateWorkspaceFailingFor("candidate-failure"),
                approvedWorkspaceFailingFor("approved-failure"),
                failingEditor);

        BridgeResponse response = assertDoesNotThrow(
                () -> handler.refresh(VaultReader.createNull(notes)));

        assertEquals(1, response.updatedCount());
        assertEquals(0, response.unchangedCount());
        assertEquals(3, response.uncertainCount());
        assertEquals("not_prepared", delegateEditor.currentValue(laterSuccessPath, "workflowStatus"));
    }

    private String essaySource(String publicId, String workflowStatusLine) {
        return "---\npublish: true\npublicCollection: blog\npublicContentType: essay\npublicId: " + publicId
                + "\nid: id-" + publicId + "\ntitle: Title\ndescription: A description.\n"
                + workflowStatusLine + "\n---\nBody.";
    }

    private CandidateWorkspace incompleteCandidateWorkspace() {
        return new CandidateWorkspace() {
            @Override
            public void install(PublicationIdentity identity, String ruBody, String enBody,
                    String ruTitle, String enTitle, String ruDescription, String enDescription,
                    ReferenceMap referenceMap) {
                // no-op: this fixture represents an already incomplete candidate directory
            }

            @Override
            public Optional<CandidatePaths> find(PublicationIdentity identity) {
                return Optional.of(CandidatePaths.of(Path.of("candidate/ru.md"), Path.of("candidate/en.md")));
            }

            @Override
            public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
                return Optional.empty();
            }
        };
    }

    private CandidateWorkspace candidateWorkspaceFailingFor(String failingPublicId) {
        return new CandidateWorkspace() {
            @Override
            public void install(PublicationIdentity identity, String ruBody, String enBody,
                    String ruTitle, String enTitle, String ruDescription, String enDescription,
                    ReferenceMap referenceMap) {
                // no-op: refresh only reads candidate state
            }

            @Override
            public Optional<CandidatePaths> find(PublicationIdentity identity) {
                if (identity.publicId().equals(failingPublicId)) {
                    throw new UncheckedIOException(new IOException("candidate directory unavailable"));
                }
                return Optional.empty();
            }

            @Override
            public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
                return Optional.empty();
            }
        };
    }

    private ApprovedSnapshotWorkspace approvedWorkspaceFailingFor(String failingPublicId) {
        return new ApprovedSnapshotWorkspace() {
            @Override
            public void install(PublicationIdentity identity, String ruBody, String enBody,
                    String ruTitle, String enTitle, String ruDescription, String enDescription,
                    ReferenceMap referenceMap) {
                // no-op: refresh only reads approved state
            }

            @Override
            public Optional<CandidatePaths> find(PublicationIdentity identity) {
                return Optional.empty();
            }

            @Override
            public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
                if (identity.publicId().equals(failingPublicId)) {
                    throw new ApprovedSnapshotIntegrityException(
                            Path.of("approved", failingPublicId), "snapshot is corrupt");
                }
                return Optional.empty();
            }
        };
    }
}
