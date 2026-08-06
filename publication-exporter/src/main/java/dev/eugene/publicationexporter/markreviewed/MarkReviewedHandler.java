package dev.eugene.publicationexporter.markreviewed;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotAlreadyExistsException;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceConfinementException;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.IoFailureMessages;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspaceConfinementException;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public final class MarkReviewedHandler {

    private static final String COMMAND = "mark-reviewed";

    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;

    public MarkReviewedHandler(CandidateWorkspace candidateWorkspace, ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace = Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
    }

    public BridgeResponse markReviewed(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        return markReviewedAdmittedEssay(
                intake.identity(), intake.body(), intake.title(), intake.description());
    }

    private BridgeResponse markReviewedAdmittedEssay(
            PublicationIdentity identity, String sourceBody, String sourceTitle, String sourceDescription) {
        Optional<CandidateSnapshot> candidate;
        Optional<CandidatePaths> approvedSnapshot;
        try {
            candidate = readCandidate(identity);
            if (candidate.isEmpty()) {
                return noCandidateResponse();
            }
            approvedSnapshot = findApprovedSnapshot(identity);
        } catch (LookupFailure failure) {
            return candidateLookupFailure(failure.getMessage());
        }
        if (approvedSnapshot.isPresent()) {
            return alreadyApprovedResponse();
        }
        List<Diagnostic> staleness = stalenessDiagnostics(
                sourceBody, sourceTitle, sourceDescription, candidate.get());
        if (!staleness.isEmpty()) {
            return BridgeResponse.stale(COMMAND, staleness);
        }
        return installApprovedSnapshot(identity, candidate.get());
    }

    private Optional<CandidateSnapshot> readCandidate(PublicationIdentity identity) {
        try {
            return candidateWorkspace.read(identity);
        } catch (UncheckedIOException failure) {
            throw new LookupFailure(IoFailureMessages.describe("Candidate lookup failed", failure));
        } catch (CandidateWorkspaceConfinementException failure) {
            throw new LookupFailure("Candidate lookup failed: " + failure.getMessage());
        }
    }

    private Optional<CandidatePaths> findApprovedSnapshot(PublicationIdentity identity) {
        try {
            return approvedSnapshotWorkspace.find(identity);
        } catch (UncheckedIOException failure) {
            throw new LookupFailure(IoFailureMessages.describe("Approved snapshot lookup failed", failure));
        } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
            throw new LookupFailure("Approved snapshot lookup failed: " + failure.getMessage());
        }
    }

    private static final class LookupFailure extends RuntimeException {
        LookupFailure(String message) {
            super(message);
        }
    }

    private List<Diagnostic> stalenessDiagnostics(
            String sourceBody, String sourceTitle, String sourceDescription, CandidateSnapshot candidate) {
        ReferenceMap referenceMap = candidate.referenceMap();
        return Stream.of(
                        sourceChangedDiagnostic(sourceBody, referenceMap),
                        sourceTitleChangedDiagnostic(sourceTitle, referenceMap),
                        sourceDescriptionChangedDiagnostic(sourceDescription, referenceMap),
                        candidateRuBodyChangedDiagnostic(candidate, referenceMap),
                        candidateRuTitleChangedDiagnostic(candidate, referenceMap),
                        candidateRuDescriptionChangedDiagnostic(candidate, referenceMap),
                        candidateEnBodyChangedDiagnostic(candidate, referenceMap),
                        candidateEnTitleChangedDiagnostic(candidate, referenceMap),
                        candidateEnDescriptionChangedDiagnostic(candidate, referenceMap))
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<Diagnostic> sourceChangedDiagnostic(String sourceBody, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(sourceBody).equals(referenceMap.ruHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Source note has changed since the candidate was prepared."));
    }

    private static Optional<Diagnostic> sourceTitleChangedDiagnostic(
            String sourceTitle, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(sourceTitle).equals(referenceMap.ruTitleHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Source note title has changed since the candidate was prepared."));
    }

    private static Optional<Diagnostic> sourceDescriptionChangedDiagnostic(
            String sourceDescription, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(sourceDescription).equals(referenceMap.ruDescriptionHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Source note description has changed since the candidate was prepared."));
    }

    private static Optional<Diagnostic> candidateRuBodyChangedDiagnostic(
            CandidateSnapshot candidate, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(candidate.ruBody()).equals(referenceMap.ruHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Candidate Russian body has changed since it was prepared."));
    }

    private static Optional<Diagnostic> candidateRuTitleChangedDiagnostic(
            CandidateSnapshot candidate, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(candidate.ruTitle()).equals(referenceMap.ruTitleHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Candidate Russian title has changed since it was prepared."));
    }

    private static Optional<Diagnostic> candidateRuDescriptionChangedDiagnostic(
            CandidateSnapshot candidate, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(candidate.ruDescription()).equals(referenceMap.ruDescriptionHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Candidate Russian description has changed since it was prepared."));
    }

    private static Optional<Diagnostic> candidateEnBodyChangedDiagnostic(
            CandidateSnapshot candidate, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(candidate.enBody()).equals(referenceMap.enHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Candidate English body has changed since it was prepared."));
    }

    private static Optional<Diagnostic> candidateEnTitleChangedDiagnostic(
            CandidateSnapshot candidate, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(candidate.enTitle()).equals(referenceMap.enTitleHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Candidate English title has changed since it was prepared."));
    }

    private static Optional<Diagnostic> candidateEnDescriptionChangedDiagnostic(
            CandidateSnapshot candidate, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(candidate.enDescription()).equals(referenceMap.enDescriptionHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Candidate English description has changed since it was prepared."));
    }

    private BridgeResponse installApprovedSnapshot(PublicationIdentity identity, CandidateSnapshot candidate) {
        try {
            approvedSnapshotWorkspace.install(
                    identity, candidate.ruBody(), candidate.enBody(),
                    candidate.ruTitle(), candidate.enTitle(),
                    candidate.ruDescription(), candidate.enDescription(),
                    candidate.referenceMap());
        } catch (ApprovedSnapshotAlreadyExistsException raceLoser) {
            return alreadyApprovedResponse();
        } catch (UncheckedIOException failure) {
            return BridgeResponse.blocked(COMMAND,
                    Diagnostic.blocking("candidate", "Approved installation failed."));
        }
        return BridgeResponse.approved(COMMAND, identity);
    }

    private static BridgeResponse candidateLookupFailure(String message) {
        return BridgeResponse.blocked(COMMAND, Diagnostic.blocking("candidate", message));
    }

    private static BridgeResponse noCandidateResponse() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("candidate", "No candidate exists to approve."));
    }

    private static BridgeResponse alreadyApprovedResponse() {
        return BridgeResponse.blocked(COMMAND, Diagnostic.blocking("candidate",
                "An approved snapshot already exists; replacing it is not yet supported."));
    }
}
