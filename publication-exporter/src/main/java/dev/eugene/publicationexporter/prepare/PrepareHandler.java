package dev.eugene.publicationexporter.prepare;

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
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.translation.EnglishTranslation;
import dev.eugene.publicationexporter.translation.TranslationFailure;
import dev.eugene.publicationexporter.translation.TranslationJob;
import dev.eugene.publicationexporter.translation.TranslationOutcome;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.WorkflowState;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

public final class PrepareHandler {

    private static final String COMMAND = "prepare";
    private static final ConcurrentMap<PublicationIdentity, ReentrantLock> INSTALL_LOCKS =
            new ConcurrentHashMap<>();

    private final TranslationWorker translationWorker;
    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final WorkflowStatusEditor workflowStatusEditor;

    public PrepareHandler(TranslationWorker translationWorker, CandidateWorkspace candidateWorkspace,
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, WorkflowStatusEditor workflowStatusEditor) {
        this.translationWorker = Objects.requireNonNull(translationWorker, "translationWorker");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.workflowStatusEditor = Objects.requireNonNull(workflowStatusEditor, "workflowStatusEditor");
    }

    public BridgeResponse prepare(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        PublicNoteIndex knownNotes = PublicNoteIndex.from(vaultReader);
        return MarkdownNormalizer.normalize(intake.body()).resolve(
                normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(
                        resolvedBody -> prepareNormalizedEssay(notePath, vaultReader, intake, resolvedBody, knownNotes),
                        PrepareHandler::transclusionBlockedFailure),
                position -> unclosedCommentFailure(position));
    }

    private BridgeResponse prepareNormalizedEssay(
            VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake,
            String normalizedBody, PublicNoteIndex knownNotes) {
        ApprovedBaselineLookup approved = lookupApprovedBaseline(intake, normalizedBody);
        if (approved.failed()) {
            return approved.failureResponse();
        }
        if (approved.snapshot().isPresent()) {
            return mirrorApprovedCandidate(intake.identity(), approved.snapshot().get());
        }
        return prepareWithInstallLock(notePath, vaultReader, intake, normalizedBody, knownNotes);
    }

    private ApprovedBaselineLookup lookupApprovedBaseline(NoteIntake.Result intake, String normalizedBody) {
        try {
            return ApprovedBaselineLookup.found(matchingApprovedBaseline(
                    intake.identity(), normalizedBody, intake.title(), intake.description()));
        } catch (UncheckedIOException failure) {
            return ApprovedBaselineLookup.failed(
                    approvedLookupFailure(IoFailureMessages.describe("Approved snapshot lookup failed", failure)));
        } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
            return ApprovedBaselineLookup.failed(
                    approvedLookupFailure("Approved snapshot lookup failed: " + failure.getMessage()));
        } catch (ApprovedSnapshotWorkspaceStateException failure) {
            return ApprovedBaselineLookup.failed(
                    approvedLookupFailure("Approved snapshot lookup failed: " + failure.getMessage()));
        }
    }

    private BridgeResponse mirrorApprovedCandidate(PublicationIdentity identity, CandidateSnapshot approved) {
        try {
            ensureCandidateMirrorsApproved(identity, approved);
        } catch (UncheckedIOException failure) {
            return candidateFailure(
                    IoFailureMessages.describe("Candidate mirror of approved snapshot failed", failure));
        } catch (CandidateWorkspaceConfinementException failure) {
            return candidateFailure("Candidate mirror of approved snapshot failed: " + failure.getMessage());
        }
        return BridgeResponse.prepared(COMMAND, identity);
    }

    private BridgeResponse prepareWithInstallLock(
            VaultRelativePath notePath, VaultReader vaultReader,
            NoteIntake.Result intake, String normalizedBody, PublicNoteIndex knownNotes) {
        ReentrantLock installLock = INSTALL_LOCKS.computeIfAbsent(intake.identity(), ignored -> new ReentrantLock());
        installLock.lock();
        try {
            return prepareAdmittedEssay(notePath, vaultReader, intake.identity(),
                    intake.sourceHash(), normalizedBody, intake.title(), intake.description(), knownNotes);
        } finally {
            installLock.unlock();
        }
    }

    private Optional<CandidateSnapshot> matchingApprovedBaseline(
            PublicationIdentity identity, String currentBody, String currentTitle, String currentDescription) {
        Optional<CandidateSnapshot> approved = approvedSnapshotWorkspace.read(identity);
        if (approved.isEmpty()) {
            return Optional.empty();
        }
        CandidateSnapshot baseline = approved.get();
        boolean unchanged = RussianDiff.between(
                baseline.ruBody(), baseline.ruTitle(), baseline.ruDescription(),
                currentBody, currentTitle, currentDescription).isEmpty();
        return unchanged ? approved : Optional.empty();
    }

    private void ensureCandidateMirrorsApproved(PublicationIdentity identity, CandidateSnapshot approved) {
        if (candidateWorkspace.find(identity).isPresent()) {
            return;
        }
        candidateWorkspace.install(identity, approved.ruBody(), approved.enBody(),
                approved.ruTitle(), approved.enTitle(), approved.ruDescription(), approved.enDescription(),
                approved.referenceMap());
    }

    private BridgeResponse prepareAdmittedEssay(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity identity, String sourceHash,
            String ruBody, String ruTitle, String ruDescription, PublicNoteIndex knownNotes) {
        TranslationJob job = TranslationJob.forSource(ruBody, ruTitle, ruDescription);
        return translateCandidate(job, ruBody, ruTitle, ruDescription).resolve(
                translation -> prepareTranslatedEssay(
                        notePath, vaultReader, identity, sourceHash,
                        ruBody, ruTitle, ruDescription, job, translation, knownNotes),
                failure -> {
                    recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
                    return translationFailure(failure);
                });
    }

    private BridgeResponse prepareTranslatedEssay(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity identity, String sourceHash,
            String ruBody, String ruTitle, String ruDescription,
            TranslationJob job,
            EnglishTranslation translation, PublicNoteIndex knownNotes) {
        String enBody = translation.body();
        String enTitle = translation.title();
        String enDescription = translation.description();

        EnglishCandidateValidator.Result validation = validateEnglishCandidate(
                ruBody, enBody, enTitle, enDescription);
        if (!validation.valid()) {
            recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
            return BridgeResponse.translationFailed(COMMAND, blockingDiagnostics(validation.diagnostics()));
        }
        return sourceFreshness(notePath, vaultReader, identity, job, knownNotes).resolve(
                currentSourceHash -> {
                    ReferenceMap referenceMap = buildReferenceMap(
                            identity, ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription);
                    BridgeResponse response = installCandidate(identity, ruBody, enBody, ruTitle, enTitle,
                            ruDescription, enDescription, referenceMap);
                    if (response.ok()) {
                        recordWorkflowStatus(notePath, currentSourceHash, WorkflowState.READY_FOR_REVIEW);
                    }
                    return response;
                },
                () -> {
                    recordStaleWorkflowStatus(notePath, vaultReader);
                    return BridgeResponse.stale(COMMAND,
                            Diagnostic.blocking(
                                    "candidate", "Source note changed while translation was in progress."));
                },
                PrepareHandler::unclosedCommentFailure,
                PrepareHandler::transclusionBlockedFailure);
    }

    private void recordWorkflowStatus(VaultRelativePath notePath, String sourceHash, String status) {
        try {
            workflowStatusEditor.write(notePath, sourceHash, status);
        } catch (UncheckedIOException ignored) {
        }
    }

    private void recordStaleWorkflowStatus(VaultRelativePath notePath, VaultReader vaultReader) {
        try {
            String currentSource = vaultReader.readSource(notePath);
            recordWorkflowStatus(
                    notePath, ContentHash.sha256Hex(currentSource), WorkflowState.STALE);
        } catch (UncheckedIOException | NoSuchElementException ignored) {
        }
    }

    private TranslationOutcome translateCandidate(
            TranslationJob job, String ruBody, String ruTitle, String ruDescription) {
        try {
            return translationWorker.translate(job, ruBody, ruTitle, ruDescription);
        } catch (UncheckedIOException failure) {
            return TranslationOutcome.failure(IoFailureMessages.describe("Translation worker I/O failed", failure));
        }
    }

    private static SourceFreshnessOutcome sourceFreshness(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity expectedIdentity, TranslationJob job, PublicNoteIndex knownNotes) {
        NoteIntake.Result current = new NoteIntake().admit(notePath, vaultReader);
        if (!current.accepted() || !expectedIdentity.equals(current.identity())) {
            return SourceFreshnessOutcome.stale();
        }
        return MarkdownNormalizer.normalize(current.body()).resolve(
                normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(
                        resolvedBody -> sourceFingerprintMatches(job, resolvedBody, current.title(), current.description())
                                ? SourceFreshnessOutcome.matches(current.sourceHash())
                                : SourceFreshnessOutcome.stale(),
                        SourceFreshnessOutcome::blockedTransclusion),
                SourceFreshnessOutcome::unclosedComment);
    }

    private static boolean sourceFingerprintMatches(
            TranslationJob job, String body, String title, String description) {
        TranslationJob currentSource = TranslationJob.forSource(body, title, description);
        return job.sourceFingerprint().equals(currentSource.sourceFingerprint());
    }

    private static EnglishCandidateValidator.Result validateEnglishCandidate(
            String ruBody, String enBody, String enTitle, String enDescription) {
        return EnglishCandidateValidator.validate(ruBody, enBody, enTitle, enDescription);
    }

    private static ReferenceMap buildReferenceMap(
            PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription) {
        return ReferenceMap.empty(
                identity,
                ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody),
                ContentHash.sha256Hex(ruTitle), ContentHash.sha256Hex(enTitle),
                ContentHash.sha256Hex(ruDescription), ContentHash.sha256Hex(enDescription));
    }

    private BridgeResponse installCandidate(
            PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription,
            ReferenceMap referenceMap) {
        try {
            candidateWorkspace.install(identity, ruBody, enBody,
                    ruTitle, enTitle, ruDescription, enDescription, referenceMap);
        } catch (UncheckedIOException failure) {
            return candidateFailure(IoFailureMessages.describe("Candidate installation failed", failure));
        } catch (CandidateWorkspaceConfinementException failure) {
            return candidateFailure("Candidate installation failed: " + failure.getMessage());
        }
        return BridgeResponse.prepared(COMMAND, identity);
    }

    private BridgeResponse translationFailure(TranslationFailure failure) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking(failure.diagnosticField(), failure.reason()));
    }

    private static Diagnostic blockingDiagnostics(List<String> diagnostics) {
        return Diagnostic.blocking("candidate", String.join(" ", diagnostics));
    }

    private static BridgeResponse candidateFailure(String message) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", message));
    }

    private static BridgeResponse approvedLookupFailure(String message) {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("approved-snapshot", message));
    }

    private static BridgeResponse unclosedCommentFailure(int position) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", "Obsidian comment starting at position " + position + " is never closed."));
    }

    private static BridgeResponse transclusionBlockedFailure(String target) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", "Transclusion target \"" + target + "\" is not a public note."));
    }

    private record ApprovedBaselineLookup(
            Optional<CandidateSnapshot> snapshot, Optional<BridgeResponse> failure) {

        private static ApprovedBaselineLookup found(Optional<CandidateSnapshot> snapshot) {
            return new ApprovedBaselineLookup(snapshot, Optional.empty());
        }

        private static ApprovedBaselineLookup failed(BridgeResponse failure) {
            return new ApprovedBaselineLookup(Optional.empty(), Optional.of(failure));
        }

        private boolean failed() {
            return failure.isPresent();
        }

        private BridgeResponse failureResponse() {
            return failure.orElseThrow();
        }
    }

}
