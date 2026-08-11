package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceConfinementException;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceStateException;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.IoFailureMessages;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.bridge.ReviewPlan;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspaceConfinementException;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.prepare.RussianDiff;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.WorkflowStateClassifier;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;

public final class InspectPublicationHandler {

    private static final String COMMAND = "inspect-publication";
    private static final String READY_FOR_REVIEW = "ready_for_review";
    private static final String WORKFLOW_STATUS_KEY = "workflowStatus";
    private static final String ABSENT = "absent";
    private static final String READY = "ready";

    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final WorkflowStateClassifier classifier = new WorkflowStateClassifier();

    public InspectPublicationHandler(
            CandidateWorkspace candidateWorkspace, ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
    }

    public BridgeResponse inspect(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake(PublicationKinds.installed()).admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        Optional<CandidatePaths> candidatePaths;
        Optional<CandidateSnapshot> candidateSnapshot;
        try {
            candidatePaths = candidateWorkspace.find(intake.identity());
            candidateSnapshot = candidatePaths.isPresent()
                    ? candidateWorkspace.read(intake.identity())
                    : Optional.empty();
        } catch (UncheckedIOException failure) {
            return candidateLookupFailure(IoFailureMessages.describe("Candidate lookup failed", failure));
        } catch (CandidateWorkspaceConfinementException failure) {
            return candidateLookupFailure("Candidate lookup failed: " + failure.getMessage());
        }
        if (candidatePaths.isPresent() && candidateSnapshot.isPresent()) {
            try {
                Optional<CandidateSnapshot> approved = approvedSnapshotWorkspace.read(intake.identity());
                if (approved.isPresent() && candidateSnapshot.get().equals(approved.get())) {
                    return BridgeResponse.essayInspected(
                            COMMAND, "ready_to_publish", intake.identity(),
                            READY, READY, ABSENT, ABSENT, null);
                }
                return readyForReviewResponse(
                        intake.identity(), candidatePaths.get(), candidateSnapshot.get(), approved);
            } catch (UncheckedIOException failure) {
                return approvedLookupFailure(
                        IoFailureMessages.describe("Approved snapshot lookup failed", failure));
            } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
                return approvedLookupFailure("Approved snapshot lookup failed: " + failure.getMessage());
            } catch (ApprovedSnapshotWorkspaceStateException failure) {
                return approvedLookupFailure("Approved snapshot lookup failed: " + failure.getMessage());
            }
        }
        return notPreparedOrReadyToPublishResponse(
                intake.identity(), intake.frontmatterString(WORKFLOW_STATUS_KEY));
    }

    private static BridgeResponse candidateLookupFailure(String message) {
        return BridgeResponse.blocked(COMMAND, Diagnostic.blocking("candidate", message));
    }

    private static BridgeResponse approvedLookupFailure(String message) {
        return BridgeResponse.blocked(COMMAND, Diagnostic.blocking("approved-snapshot", message));
    }

    private BridgeResponse readyForReviewResponse(
            PublicationIdentity identity, CandidatePaths candidatePaths, CandidateSnapshot candidateSnapshot,
            Optional<CandidateSnapshot> approved) {
        ReviewPlan reviewPlan = reviewPlanFor(candidatePaths, candidateSnapshot, approved);
        String approvedSnapshotState = approved.isPresent() ? READY : ABSENT;
        return BridgeResponse.essayInspected(
                COMMAND, READY_FOR_REVIEW, identity,
                READY, approvedSnapshotState, ABSENT, ABSENT, reviewPlan);
    }

    private ReviewPlan reviewPlanFor(
            CandidatePaths candidatePaths, CandidateSnapshot candidateSnapshot,
            Optional<CandidateSnapshot> approved) {
        if (approved.isEmpty()) {
            return ReviewPlan.firstPublication(
                    candidatePaths,
                    candidateSnapshot.ruTitle(), candidateSnapshot.enTitle(),
                    candidateSnapshot.ruDescription(), candidateSnapshot.enDescription());
        }
        CandidateSnapshot baseline = approved.get();
        RussianDiff diff = RussianDiff.between(
                baseline.ruBody(), baseline.ruTitle(), baseline.ruDescription(),
                candidateSnapshot.ruBody(), candidateSnapshot.ruTitle(), candidateSnapshot.ruDescription());
        return ReviewPlan.changedPublication(
                candidatePaths,
                candidateSnapshot.ruTitle(), candidateSnapshot.enTitle(),
                candidateSnapshot.ruDescription(), candidateSnapshot.enDescription(), diff);
    }

    private BridgeResponse notPreparedOrReadyToPublishResponse(
            PublicationIdentity identity, Optional<String> persistedWorkflowStatus) {
        boolean approvedPresent;
        try {
            approvedPresent = approvedSnapshotWorkspace.read(identity).isPresent();
        } catch (UncheckedIOException failure) {
            return approvedLookupFailure(IoFailureMessages.describe("Approved snapshot lookup failed", failure));
        } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
            return approvedLookupFailure("Approved snapshot lookup failed: " + failure.getMessage());
        } catch (ApprovedSnapshotWorkspaceStateException failure) {
            return approvedLookupFailure("Approved snapshot lookup failed: " + failure.getMessage());
        }
        String status = classifier.classify(false, approvedPresent, persistedWorkflowStatus);
        String approvedState = approvedPresent ? READY : ABSENT;
        return BridgeResponse.essayInspected(
                COMMAND, status, identity,
                ABSENT, approvedState, ABSENT, ABSENT, null);
    }
}
