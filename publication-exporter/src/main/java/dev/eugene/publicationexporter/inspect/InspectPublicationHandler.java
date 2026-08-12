package dev.eugene.publicationexporter.inspect;

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
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.WorkflowStateClassifier;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.NoSuchElementException;

public final class InspectPublicationHandler {

    private static final String COMMAND = "inspect-publication";
    private static final String READY_FOR_REVIEW = "ready_for_review";
    private static final String WORKFLOW_STATUS_KEY = "workflowStatus";
    private static final String ABSENT = "absent";
    private static final String READY = "ready";

    private final NoteIntake noteIntake;
    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final WorkflowStateClassifier classifier = new WorkflowStateClassifier();

    public InspectPublicationHandler(
            NoteIntake noteIntake, CandidateWorkspace candidateWorkspace, ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
        this.noteIntake = Objects.requireNonNull(noteIntake, "noteIntake");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
    }

    public BridgeResponse inspect(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = noteIntake.admit(notePath, vaultReader);
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
            } catch (NoSuchElementException failure) {
                return candidateLookupFailure("Candidate lookup failed: " + failure.getMessage());
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
                    fieldValue(candidateSnapshot.ruFields(), "title"),
                    fieldValue(candidateSnapshot.enFields(), "title"),
                    fieldValue(candidateSnapshot.ruFields(), "description"),
                    fieldValue(candidateSnapshot.enFields(), "description"));
        }
        CandidateSnapshot baseline = approved.get();
        RussianDiff diff = alignedFieldStructure(baseline.ruFields(), candidateSnapshot.ruFields())
                ? RussianDiff.between(
                        baseline.ruBody(), baseline.ruFields(),
                        candidateSnapshot.ruBody(), candidateSnapshot.ruFields())
                : RussianDiff.betweenBodies(
                        reviewPlanDiffInput(baseline.ruFields(), baseline.ruBody()),
                        reviewPlanDiffInput(candidateSnapshot.ruFields(), candidateSnapshot.ruBody()));
        return ReviewPlan.changedPublication(
                candidatePaths,
                fieldValue(candidateSnapshot.ruFields(), "title"),
                fieldValue(candidateSnapshot.enFields(), "title"),
                fieldValue(candidateSnapshot.ruFields(), "description"),
                fieldValue(candidateSnapshot.enFields(), "description"), diff);
    }

    private static boolean alignedFieldStructure(List<PublicField> baselineFields, List<PublicField> currentFields) {
        if (baselineFields.size() != currentFields.size()) {
            return false;
        }
        for (int i = 0; i < baselineFields.size(); i++) {
            if (!baselineFields.get(i).key().equals(currentFields.get(i).key())) {
                return false;
            }
        }
        return true;
    }

    private static String reviewPlanDiffInput(List<PublicField> fields, String body) {
        StringBuilder content = new StringBuilder();
        for (PublicField field : fields) {
            appendFieldLines(content, field);
        }
        if (!fields.isEmpty() && !body.isEmpty()) {
            content.append('\n');
        }
        content.append(body);
        return content.toString();
    }

    private static void appendFieldLines(StringBuilder content, PublicField field) {
        for (String line : normalizedLines(field.value())) {
            content.append(field.key()).append(": ").append(line).append('\n');
        }
    }

    private static String[] normalizedLines(String value) {
        if (value.isEmpty()) {
            return new String[] { "" };
        }
        return value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    private static String fieldValue(List<PublicField> fields, String key) {
        return PublicField.value(fields, key)
                .orElseThrow(() -> new NoSuchElementException(
                        "candidate snapshot is missing required field '" + key + "'"));
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
