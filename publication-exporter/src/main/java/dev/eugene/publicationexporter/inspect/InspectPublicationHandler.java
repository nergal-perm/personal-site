package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.bridge.ReviewPlan;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.Objects;
import java.util.Optional;

public final class InspectPublicationHandler {

    private static final String COMMAND = "inspect-publication";
    private static final String NOT_PREPARED = "not_prepared";
    private static final String READY_FOR_REVIEW = "ready_for_review";
    private static final String ABSENT = "absent";
    private static final String READY = "ready";

    private final CandidateWorkspace candidateWorkspace;

    public InspectPublicationHandler(CandidateWorkspace candidateWorkspace) {
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
    }

    public BridgeResponse inspect(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        Optional<CandidatePaths> candidate = candidateWorkspace.find(intake.identity());
        if (candidate.isPresent()) {
            return readyForReviewResponse(intake.identity(), candidate.get());
        }
        return notPreparedResponse(intake.identity());
    }

    private BridgeResponse readyForReviewResponse(PublicationIdentity identity, CandidatePaths candidatePaths) {
        return BridgeResponse.essayInspected(
                COMMAND, READY_FOR_REVIEW, identity,
                READY, ABSENT, ABSENT, ABSENT, ReviewPlan.firstPublication(candidatePaths));
    }

    private BridgeResponse notPreparedResponse(PublicationIdentity identity) {
        return BridgeResponse.essayInspected(
                COMMAND, NOT_PREPARED, identity,
                ABSENT, ABSENT, ABSENT, ABSENT, null);
    }
}
