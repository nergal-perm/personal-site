package dev.eugene.publicationexporter.markreviewed;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotAlreadyExistsException;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotApprovalInProgressException;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceConfinementException;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceStateException;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.IoFailureMessages;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspaceConfinementException;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.WorkflowState;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public final class MarkReviewedHandler {

    private static final String COMMAND = "mark-reviewed";
    private static final ConcurrentMap<PublicationIdentity, ReentrantLock> APPROVAL_LOCKS =
            new ConcurrentHashMap<>();

    private final NoteIntake noteIntake;
    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final WorkflowStatusEditor workflowStatusEditor;

    public MarkReviewedHandler(NoteIntake noteIntake, CandidateWorkspace candidateWorkspace,
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, WorkflowStatusEditor workflowStatusEditor) {
        this.noteIntake = Objects.requireNonNull(noteIntake, "noteIntake");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace = Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.workflowStatusEditor = Objects.requireNonNull(workflowStatusEditor, "workflowStatusEditor");
    }

    public BridgeResponse markReviewed(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = noteIntake.admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        return markReviewedAdmittedEssay(notePath, vaultReader, intake.identity());
    }

    private BridgeResponse markReviewedAdmittedEssay(
            VaultRelativePath notePath, VaultReader vaultReader, PublicationIdentity identity) {
        ReentrantLock lock = APPROVAL_LOCKS.computeIfAbsent(identity, ignored -> new ReentrantLock());
        lock.lock();
        try {
            try {
                return approvedSnapshotWorkspace.withApprovalLock(
                        identity, () -> markReviewedWithFreshSource(notePath, vaultReader, identity));
            } catch (ApprovedSnapshotApprovalInProgressException collision) {
                return BridgeResponse.stale(COMMAND,
                        Diagnostic.blocking("approved-snapshot", collision.getMessage()));
            } catch (UncheckedIOException failure) {
                return BridgeResponse.blocked(COMMAND,
                        Diagnostic.blocking("approved-snapshot",
                                IoFailureMessages.describe("Approved snapshot operation failed", failure)));
            } catch (ApprovedSnapshotWorkspaceConfinementException | ApprovedSnapshotWorkspaceStateException failure) {
                return BridgeResponse.blocked(COMMAND,
                        Diagnostic.blocking("approved-snapshot",
                                "Approved snapshot operation failed: " + failure.getMessage()));
            }
        } finally {
            lock.unlock();
        }
    }

    private BridgeResponse markReviewedWithFreshSource(
            VaultRelativePath notePath, VaultReader vaultReader, PublicationIdentity lockedIdentity) {
        NoteIntake.Result current = noteIntake.admit(notePath, vaultReader);
        if (!current.accepted()) {
            return BridgeResponse.blocked(COMMAND, current.diagnostics());
        }
        if (!lockedIdentity.equals(current.identity())) {
            return BridgeResponse.stale(COMMAND,
                    Diagnostic.blocking("candidate", "Source publication identity changed while approval waited."));
        }
        return markReviewedUnderLock(
                notePath, lockedIdentity, current.sourceHash(), current.body(),
                fieldsOf(current), current.structuredData());
    }

    private BridgeResponse markReviewedUnderLock(
            VaultRelativePath notePath, PublicationIdentity identity, String sourceHash,
            String sourceBody, List<PublicField> sourceFields,
            String sourceStructuredData) {
        Optional<CandidateSnapshot> candidate;
        try {
            candidate = readCandidate(identity);
            if (candidate.isEmpty()) {
                return noCandidateResponse();
            }
        } catch (LookupFailure failure) {
            return candidateLookupFailure(failure.getMessage());
        }
        List<Diagnostic> staleness = stalenessDiagnostics(
                sourceBody, sourceFields, sourceStructuredData, candidate.get());
        if (!staleness.isEmpty()) {
            return BridgeResponse.stale(COMMAND, staleness);
        }
        return installApprovedSnapshot(notePath, sourceHash, identity, candidate.get());
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

    private static final class LookupFailure extends RuntimeException {
        LookupFailure(String message) {
            super(message);
        }
    }

    private List<Diagnostic> stalenessDiagnostics(
            String sourceBody, List<PublicField> sourceFields,
            String sourceStructuredData, CandidateSnapshot candidate) {
        ReferenceMap referenceMap = candidate.referenceMap();
        return Stream.of(
                        sourceChangedDiagnostic(sourceBody, referenceMap),
                        sourceFieldsChangedDiagnostic(sourceFields, referenceMap),
                        sourceStructuredDataChangedDiagnostic(sourceStructuredData, referenceMap),
                        candidateRuBodyChangedDiagnostic(candidate, referenceMap),
                        candidateRuFieldsChangedDiagnostic(candidate, referenceMap),
                        candidateStructuredDataChangedDiagnostic(candidate, referenceMap),
                        candidateEnBodyChangedDiagnostic(candidate, referenceMap),
                        candidateEnFieldsChangedDiagnostic(candidate, referenceMap))
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<Diagnostic> sourceChangedDiagnostic(String sourceBody, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(sourceBody).equals(referenceMap.sourceBodyHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Source note has changed since the candidate was prepared."));
    }

    private static Optional<Diagnostic> sourceFieldsChangedDiagnostic(
            List<PublicField> sourceFields, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(PublicFieldsCodec.write(sourceFields)).equals(referenceMap.ruFieldsHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Source note public fields have changed since the candidate was prepared."));
    }

    private static Optional<Diagnostic> sourceStructuredDataChangedDiagnostic(
            String sourceStructuredData, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(sourceStructuredData).equals(referenceMap.structuredDataHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Source note structured data has changed since the candidate was prepared."));
    }

    private static Optional<Diagnostic> candidateRuBodyChangedDiagnostic(
            CandidateSnapshot candidate, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(candidate.ruBody()).equals(referenceMap.ruHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Candidate Russian body has changed since it was prepared."));
    }

    private static Optional<Diagnostic> candidateRuFieldsChangedDiagnostic(
            CandidateSnapshot candidate, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(PublicFieldsCodec.write(candidate.ruFields())).equals(referenceMap.ruFieldsHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Candidate Russian public fields have changed since it was prepared."));
    }

    private static Optional<Diagnostic> candidateStructuredDataChangedDiagnostic(
            CandidateSnapshot candidate, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(candidate.structuredData()).equals(referenceMap.structuredDataHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Candidate structured data has changed since it was prepared."));
    }

    private static Optional<Diagnostic> candidateEnBodyChangedDiagnostic(
            CandidateSnapshot candidate, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(candidate.enBody()).equals(referenceMap.enHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Candidate English body has changed since it was prepared."));
    }

    private static Optional<Diagnostic> candidateEnFieldsChangedDiagnostic(
            CandidateSnapshot candidate, ReferenceMap referenceMap) {
        if (ContentHash.sha256Hex(PublicFieldsCodec.write(candidate.enFields())).equals(referenceMap.enFieldsHash())) {
            return Optional.empty();
        }
        return Optional.of(Diagnostic.blocking("candidate",
                "Candidate English public fields have changed since it was prepared."));
    }

    private BridgeResponse installApprovedSnapshot(
            VaultRelativePath notePath, String sourceHash, PublicationIdentity identity, CandidateSnapshot candidate) {
        try {
            approvedSnapshotWorkspace.install(identity, candidate);
        } catch (ApprovedSnapshotAlreadyExistsException raceLoser) {
            return alreadyApprovedResponse();
        } catch (UncheckedIOException failure) {
            return BridgeResponse.blocked(COMMAND,
                    Diagnostic.blocking("candidate", "Approved installation failed."));
        } catch (ApprovedSnapshotWorkspaceConfinementException | ApprovedSnapshotWorkspaceStateException failure) {
            return BridgeResponse.blocked(COMMAND,
                    Diagnostic.blocking("approved-snapshot", "Approved installation failed: " + failure.getMessage()));
        }
        try {
            WorkflowStatusEditor.Result result = workflowStatusEditor.write(
                    notePath, sourceHash, WorkflowState.READY_TO_PUBLISH);
            if (!result.isWritten()) {
                return BridgeResponse.blocked(COMMAND,
                        Diagnostic.blocking("workflowStatus", result.blockedReason()));
            }
        } catch (UncheckedIOException failure) {
            return BridgeResponse.blocked(COMMAND,
                    Diagnostic.blocking("workflowStatus",
                            IoFailureMessages.describe("Workflow status update failed", failure)));
        }
        return BridgeResponse.approved(COMMAND, identity);
    }

    private static List<PublicField> fieldsOf(NoteIntake.Result intake) {
        return intake.fields();
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
