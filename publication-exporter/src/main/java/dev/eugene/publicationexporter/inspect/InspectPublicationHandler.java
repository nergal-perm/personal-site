package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
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

import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;

public final class InspectPublicationHandler {

    private static final String COMMAND = "inspect-publication";
    private static final String NOT_PREPARED = "not_prepared";
    private static final String READY_FOR_REVIEW = "ready_for_review";
    private static final String ABSENT = "absent";
    private static final String READY = "ready";

    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;

    public InspectPublicationHandler(CandidateWorkspace candidateWorkspace) {
        this(candidateWorkspace, ApprovedSnapshotWorkspace.createNull());
    }

    public InspectPublicationHandler(
            CandidateWorkspace candidateWorkspace, ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
    }

    public BridgeResponse inspect(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
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
            return readyForReviewResponse(intake.identity(), candidatePaths.get(), candidateSnapshot.get());
        }
        return notPreparedResponse(intake.identity());
    }

    private static BridgeResponse candidateLookupFailure(String message) {
        return BridgeResponse.blocked(COMMAND, Diagnostic.blocking("candidate", message));
    }

    private BridgeResponse readyForReviewResponse(
            PublicationIdentity identity, CandidatePaths candidatePaths, CandidateSnapshot candidateSnapshot) {
        Optional<CandidateSnapshot> approved = approvedSnapshotWorkspace.read(identity);
        ReviewPlan reviewPlan = approved.isPresent()
                ? ReviewPlan.changedPublication(
                        candidatePaths,
                        candidateSnapshot.ruTitle(), candidateSnapshot.enTitle(),
                        candidateSnapshot.ruDescription(), candidateSnapshot.enDescription(),
                        RussianDiff.betweenBodies(approved.get().ruBody(), candidateSnapshot.ruBody()))
                : ReviewPlan.firstPublication(
                        candidatePaths,
                        candidateSnapshot.ruTitle(), candidateSnapshot.enTitle(),
                        candidateSnapshot.ruDescription(), candidateSnapshot.enDescription());
        return BridgeResponse.essayInspected(
                COMMAND, READY_FOR_REVIEW, identity,
                READY, approved.isPresent() ? READY : ABSENT, ABSENT, ABSENT, reviewPlan);
    }

    private BridgeResponse notPreparedResponse(PublicationIdentity identity) {
        return BridgeResponse.essayInspected(
                COMMAND, NOT_PREPARED, identity,
                ABSENT, ABSENT, ABSENT, ABSENT, null);
    }
}
