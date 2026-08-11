package dev.eugene.publicationexporter.refresh;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.WorkflowStateClassifier;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;

import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;

public final class RefreshPublicationQueueHandler {

    private static final String COMMAND = "refresh-publication-queue";
    private static final String WORKFLOW_STATUS_KEY = "workflowStatus";

    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final WorkflowStatusEditor workflowStatusEditor;
    private final WorkflowStateClassifier classifier = new WorkflowStateClassifier();

    public RefreshPublicationQueueHandler(CandidateWorkspace candidateWorkspace,
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, WorkflowStatusEditor workflowStatusEditor) {
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.workflowStatusEditor = Objects.requireNonNull(workflowStatusEditor, "workflowStatusEditor");
    }

    public BridgeResponse refresh(VaultReader vaultReader) {
        Objects.requireNonNull(vaultReader, "vaultReader");
        int updated = 0;
        int unchanged = 0;
        int uncertain = 0;
        for (VaultRelativePath notePath : vaultReader.listPublishCandidates()) {
            ReconcileOutcome outcome = reconcileOne(notePath, vaultReader);
            switch (outcome) {
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case UNCERTAIN -> uncertain++;
                case EXCLUDED -> { /* not admitted; not a queue member at all */ }
            }
        }
        return BridgeResponse.queueRefreshed(COMMAND, updated, unchanged, uncertain);
    }

    private ReconcileOutcome reconcileOne(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake(PublicationKinds.installed()).admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return ReconcileOutcome.EXCLUDED;
        }
        Optional<CandidatePaths> candidatePaths;
        Optional<CandidateSnapshot> candidateSnapshot;
        try {
            candidatePaths = candidateWorkspace.find(intake.identity());
            candidateSnapshot = candidatePaths.isPresent()
                    ? candidateWorkspace.read(intake.identity())
                    : Optional.empty();
        } catch (RuntimeException failure) {
            return ReconcileOutcome.UNCERTAIN;
        }
        if (candidatePaths.isPresent() && candidateSnapshot.isEmpty()) {
            return ReconcileOutcome.UNCERTAIN;
        }
        Optional<CandidateSnapshot> approvedSnapshot;
        try {
            approvedSnapshot = approvedSnapshotWorkspace.read(intake.identity());
        } catch (RuntimeException failure) {
            return ReconcileOutcome.UNCERTAIN;
        }
        boolean candidateRequiresReview = candidatePaths.isPresent() && candidateSnapshot.isPresent()
                && approvedSnapshot.map(approved -> !candidateSnapshot.get().equals(approved)).orElse(true);
        boolean approvedPresent = approvedSnapshot.isPresent();
        Optional<String> persisted = intake.frontmatterString(WORKFLOW_STATUS_KEY);
        String classified = classifier.classify(candidateRequiresReview, approvedPresent, persisted);
        if (persisted.isPresent() && persisted.get().equals(classified)) {
            return ReconcileOutcome.UNCHANGED;
        }
        try {
            WorkflowStatusEditor.Result write =
                    workflowStatusEditor.write(notePath, intake.sourceHash(), classified);
            return write.isWritten() ? ReconcileOutcome.UPDATED : ReconcileOutcome.UNCERTAIN;
        } catch (UncheckedIOException failure) {
            return ReconcileOutcome.UNCERTAIN;
        }
    }

    private enum ReconcileOutcome {
        UPDATED, UNCHANGED, UNCERTAIN, EXCLUDED
    }
}
