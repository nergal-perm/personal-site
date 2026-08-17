package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceConfinementException;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceStateException;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.IoFailureMessages;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateAsset;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspaceConfinementException;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.translation.EnglishTranslation;
import dev.eugene.publicationexporter.translation.TranslationFailure;
import dev.eugene.publicationexporter.translation.TranslationJob;
import dev.eugene.publicationexporter.translation.TranslationOutcome;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultAssetReader;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.WorkflowState;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public final class PrepareHandler {

    private static final String COMMAND = "prepare";
    private static final ConcurrentMap<PublicationIdentity, ReentrantLock> INSTALL_LOCKS =
            new ConcurrentHashMap<>();

    private final NoteIntake noteIntake;
    private final TranslationWorker translationWorker;
    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final WorkflowStatusEditor workflowStatusEditor;

    public PrepareHandler(NoteIntake noteIntake, TranslationWorker translationWorker, CandidateWorkspace candidateWorkspace,
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, WorkflowStatusEditor workflowStatusEditor) {
        this.noteIntake = Objects.requireNonNull(noteIntake, "noteIntake");
        this.translationWorker = Objects.requireNonNull(translationWorker, "translationWorker");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.workflowStatusEditor = Objects.requireNonNull(workflowStatusEditor, "workflowStatusEditor");
    }

    public BridgeResponse prepare(
            VaultRelativePath notePath, VaultReader vaultReader, VaultAssetReader vaultAssetReader) {
        NoteIntake.Result intake = noteIntake.admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        PublicNoteIndex knownNotes;
        try {
            knownNotes = PublicNoteIndex.from(vaultReader, noteIntake);
        } catch (UncheckedIOException failure) {
            return knownNotesLookupFailure(failure);
        }
        String sourceStem = PublicNoteIndex.filenameStem(notePath);
        String sourceId = intake.frontmatterString("id").orElseThrow();
        return MarkdownNormalizer.normalize(intake.body()).resolve(
                normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(
                        (resolvedBody, occurrences) -> prepareAfterIdentityCheck(
                                notePath, vaultReader, intake, resolvedBody, knownNotes, vaultAssetReader,
                                sourceStem, sourceId, occurrences),
                        PrepareHandler::transclusionBlockedFailure),
                position -> unclosedCommentFailure(position));
    }

    private BridgeResponse prepareAfterIdentityCheck(
            VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake,
            String resolvedBody, PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader,
            String sourceStem, String sourceId, List<LinkOccurrence> occurrences) {
        Set<String> privateTargetStems = occurrences.stream()
                .filter(occurrence -> occurrence.route().isEmpty())
                .map(LinkOccurrence::targetStem)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        OccurrenceContext occurrenceContext;
        if (occurrences.isEmpty()) {
            occurrenceContext = OccurrenceContext.empty();
            return prepareAfterAssetResolution(
                    notePath, vaultReader, intake, resolvedBody, knownNotes, vaultAssetReader, occurrenceContext);
        }
        VaultSourceIdentityIndex identityIndex;
        try {
            identityIndex = VaultSourceIdentityIndex.from(vaultReader);
        } catch (UncheckedIOException | NoSuchElementException failure) {
            return vaultSourceIdentityLookupFailure(failure);
        }
        occurrenceContext = new OccurrenceContext(occurrences, Optional.of(identityIndex));
        if (privateTargetStems.isEmpty()) {
            return prepareAfterAssetResolution(
                    notePath, vaultReader, intake, resolvedBody, knownNotes, vaultAssetReader, occurrenceContext);
        }
        return DirectTargetIdentityCheck.verify(sourceStem, sourceId, privateTargetStems, identityIndex)
                .resolve(
                        () -> prepareAfterAssetResolution(
                                notePath, vaultReader, intake, resolvedBody, knownNotes, vaultAssetReader,
                                occurrenceContext),
                        PrepareHandler::directTargetIdentityBlockedFailure);
    }

    private BridgeResponse prepareAfterAssetResolution(
            VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake,
            String resolvedBody, PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader,
            OccurrenceContext occurrenceContext) {
        AssetResolutionOutcome outcome;
        try {
            outcome = AssetResolver.resolve(resolvedBody, vaultAssetReader);
        } catch (UncheckedIOException failure) {
            return assetResolutionLookupFailure(failure);
        }
        return outcome.resolve(
                (assetResolvedBody, assets) -> prepareNormalizedEssay(notePath, vaultReader,
                        intake, assetResolvedBody, assets, knownNotes, vaultAssetReader, occurrenceContext),
                PrepareHandler::assetBlockedFailure);
    }

    private BridgeResponse prepareNormalizedEssay(
            VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake,
            String normalizedBody, List<CandidateAsset> assets, PublicNoteIndex knownNotes,
            VaultAssetReader vaultAssetReader, OccurrenceContext occurrenceContext) {
        ApprovedBaselineLookup approved = lookupApprovedBaseline(intake, normalizedBody);
        if (approved.failed()) {
            return approved.failureResponse();
        }
        if (approved.snapshot().isPresent()) {
            return mirrorApprovedCandidate(intake.identity(), approved.snapshot().get(), assets);
        }
        return prepareWithInstallLock(notePath, vaultReader, intake, normalizedBody, assets,
                knownNotes, vaultAssetReader, occurrenceContext);
    }

    private ApprovedBaselineLookup lookupApprovedBaseline(NoteIntake.Result intake, String normalizedBody) {
        try {
            return ApprovedBaselineLookup.found(matchingApprovedBaseline(
                    intake.identity(), normalizedBody, fieldsOf(intake), intake.structuredData()));
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

    private BridgeResponse mirrorApprovedCandidate(
            PublicationIdentity identity, CandidateSnapshot approved, List<CandidateAsset> assets) {
        try {
            ensureCandidateMirrorsApproved(identity, approved, assets);
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
            NoteIntake.Result intake, String normalizedBody, List<CandidateAsset> assets,
            PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader,
            OccurrenceContext occurrenceContext) {
        ReentrantLock installLock = INSTALL_LOCKS.computeIfAbsent(intake.identity(), ignored -> new ReentrantLock());
        installLock.lock();
        try {
            return prepareAdmittedEssay(notePath, vaultReader, intake.identity(),
                    intake.sourceHash(), normalizedBody, fieldsOf(intake), intake.structuredData(), assets,
                    knownNotes, vaultAssetReader, occurrenceContext);
        } finally {
            installLock.unlock();
        }
    }

    private Optional<CandidateSnapshot> matchingApprovedBaseline(
            PublicationIdentity identity, String currentBody, List<PublicField> currentFields,
            String currentStructuredData) {
        Optional<CandidateSnapshot> approved = approvedSnapshotWorkspace.read(identity);
        if (approved.isEmpty()) {
            return Optional.empty();
        }
        CandidateSnapshot baseline = approved.get();
        if (!alignedFieldStructure(baseline.ruFields(), currentFields)) {
            return Optional.empty();
        }
        boolean unchanged = RussianDiff.between(
                baseline.ruBody(), baseline.ruFields(), currentBody, currentFields).isEmpty()
                && baseline.structuredData().equals(currentStructuredData);
        return unchanged ? approved : Optional.empty();
    }

    private boolean alignedFieldStructure(List<PublicField> baselineFields, List<PublicField> currentFields) {
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

    private void ensureCandidateMirrorsApproved(
            PublicationIdentity identity, CandidateSnapshot approved, List<CandidateAsset> assets) {
        if (candidateWorkspace.find(identity).isPresent()) {
            return;
        }
        candidateWorkspace.install(identity, approved, assets);
    }

    private BridgeResponse prepareAdmittedEssay(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity identity, String sourceHash,
            String ruBody, List<PublicField> ruFields, String structuredData, List<CandidateAsset> assets,
            PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader,
            OccurrenceContext occurrenceContext) {
        TranslationJob job = TranslationJob.forSource(ruBody, ruFields);
        return translateCandidate(job, ruBody, ruFields).resolve(
                translation -> prepareTranslatedEssay(
                        notePath, vaultReader, identity, sourceHash,
                        ruBody, ruFields, structuredData, assets, job, translation, knownNotes, vaultAssetReader,
                        occurrenceContext),
                failure -> {
                    recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
                    return translationFailure(failure);
                });
    }

    private BridgeResponse prepareTranslatedEssay(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity identity, String sourceHash,
            String ruBody, List<PublicField> ruFields, String structuredData,
            List<CandidateAsset> assets, TranslationJob job,
            EnglishTranslation translation, PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader,
            OccurrenceContext occurrenceContext) {
        String enBody = translation.body();
        List<PublicField> enFields = translation.fields();

        EnglishCandidateValidator.Result validation = validateEnglishCandidate(
                ruBody, ruFields, enBody, enFields);
        if (!validation.valid()) {
            recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
            return BridgeResponse.translationFailed(COMMAND, blockingDiagnostics(validation.diagnostics()));
        }
        SourceFreshnessOutcome freshness;
        try {
            freshness = sourceFreshness(
                    notePath, vaultReader, identity, job, structuredData,
                    knownNotes, vaultAssetReader, noteIntake);
        } catch (UncheckedIOException failure) {
            return assetResolutionLookupFailure(failure);
        }
        return freshness.resolve(
                currentSourceHash -> {
                    ReferenceMap referenceMap = buildReferenceMap(
                            identity, ruBody, enBody, ruFields, enFields, structuredData);
                    BridgeResponse response = installCandidate(identity, ruBody, enBody, ruFields, enFields,
                            structuredData, referenceMap, assets);
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
                PrepareHandler::transclusionBlockedFailure,
                PrepareHandler::assetBlockedFailure);
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
            TranslationJob job, String ruBody, List<PublicField> ruFields) {
        try {
            return translationWorker.translate(job, ruBody, ruFields);
        } catch (UncheckedIOException failure) {
            return TranslationOutcome.failure(IoFailureMessages.describe("Translation worker I/O failed", failure));
        }
    }

    private static SourceFreshnessOutcome sourceFreshness(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity expectedIdentity, TranslationJob job, String expectedStructuredData,
            PublicNoteIndex knownNotes,
            VaultAssetReader vaultAssetReader, NoteIntake noteIntake) {
        NoteIntake.Result current = noteIntake.admit(notePath, vaultReader);
        if (!current.accepted() || !expectedIdentity.equals(current.identity())) {
            return SourceFreshnessOutcome.stale();
        }
        return MarkdownNormalizer.normalize(current.body()).resolve(
                normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(
                        (resolvedBody, ignoredOccurrences) -> AssetResolver.resolve(
                                resolvedBody, vaultAssetReader).resolve(
                                (assetResolvedBody, ignoredAssets) ->
                                        sourceFingerprintMatches(job, assetResolvedBody, fieldsOf(current))
                                                && expectedStructuredData.equals(current.structuredData())
                                                ? SourceFreshnessOutcome.matches(current.sourceHash())
                                                : SourceFreshnessOutcome.stale(),
                                SourceFreshnessOutcome::assetBlocked),
                        SourceFreshnessOutcome::blockedTransclusion),
                SourceFreshnessOutcome::unclosedComment);
    }

    private static boolean sourceFingerprintMatches(
            TranslationJob job, String body, List<PublicField> fields) {
        TranslationJob currentSource = TranslationJob.forSource(body, fields);
        return job.sourceFingerprint().equals(currentSource.sourceFingerprint());
    }

    private static EnglishCandidateValidator.Result validateEnglishCandidate(
            String ruBody, List<PublicField> ruFields, String enBody, List<PublicField> enFields) {
        return EnglishCandidateValidator.validate(ruBody, ruFields, enBody, enFields);
    }

    private static ReferenceMap buildReferenceMap(
            PublicationIdentity identity, String ruBody, String enBody,
            List<PublicField> ruFields, List<PublicField> enFields, String structuredData) {
        return ReferenceMap.empty(
                identity,
                ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody),
                ContentHash.sha256Hex(PublicFieldsCodec.write(ruFields)),
                ContentHash.sha256Hex(PublicFieldsCodec.write(enFields)),
                ContentHash.sha256Hex(structuredData));
    }

    private BridgeResponse installCandidate(
            PublicationIdentity identity, String ruBody, String enBody,
            List<PublicField> ruFields, List<PublicField> enFields, String structuredData,
            ReferenceMap referenceMap, List<CandidateAsset> assets) {
        try {
            candidateWorkspace.install(identity,
                    CandidateSnapshot.of(ruBody, enBody, ruFields, enFields, structuredData, referenceMap),
                    assets);
        } catch (UncheckedIOException failure) {
            return candidateFailure(IoFailureMessages.describe("Candidate installation failed", failure));
        } catch (CandidateWorkspaceConfinementException failure) {
            return candidateFailure("Candidate installation failed: " + failure.getMessage());
        } catch (UnsupportedOperationException failure) {
            return candidateFailure("Candidate installation failed: " + failure.getMessage());
        }
        return BridgeResponse.prepared(COMMAND, identity);
    }

    private static List<PublicField> fieldsOf(NoteIntake.Result intake) {
        return intake.fields();
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

    private static BridgeResponse knownNotesLookupFailure(UncheckedIOException failure) {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking(
                        "known-notes", IoFailureMessages.describe("Known note lookup failed", failure)));
    }

    private static BridgeResponse vaultSourceIdentityLookupFailure(UncheckedIOException failure) {
        return vaultSourceIdentityLookupFailure((RuntimeException) failure);
    }

    private static BridgeResponse vaultSourceIdentityLookupFailure(NoSuchElementException failure) {
        return vaultSourceIdentityLookupFailure((RuntimeException) failure);
    }

    private static BridgeResponse vaultSourceIdentityLookupFailure(RuntimeException failure) {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking(
                        "source-identity", vaultSourceIdentityLookupFailureMessage(failure)));
    }

    private static String vaultSourceIdentityLookupFailureMessage(RuntimeException failure) {
        if (failure instanceof UncheckedIOException ioFailure) {
            return IoFailureMessages.describe("Source identity lookup failed", ioFailure);
        }
        String detail = failure.getMessage();
        return detail == null || detail.isBlank()
                ? "Source identity lookup failed."
                : "Source identity lookup failed: " + detail;
    }

    private record OccurrenceContext(
            List<LinkOccurrence> occurrences, Optional<VaultSourceIdentityIndex> identityIndex) {

        private OccurrenceContext {
            occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
            identityIndex = Objects.requireNonNull(identityIndex, "identityIndex");
        }

        private static OccurrenceContext empty() {
            return new OccurrenceContext(List.of(), Optional.empty());
        }
    }

    private static BridgeResponse assetResolutionLookupFailure(UncheckedIOException failure) {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking(
                        "assets", IoFailureMessages.describe("Asset resolution failed", failure)));
    }

    private static BridgeResponse unclosedCommentFailure(int position) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", "Obsidian comment starting at position " + position + " is never closed."));
    }

    private static BridgeResponse transclusionBlockedFailure(String target) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", "Transclusion target \"" + target + "\" is not a public note."));
    }

    private static BridgeResponse directTargetIdentityBlockedFailure(String reason) {
        return BridgeResponse.blocked(COMMAND, Diagnostic.blocking("semantic-references", reason));
    }

    private static BridgeResponse assetBlockedFailure(String reference) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", "Asset reference \"" + reference + "\" could not be resolved."));
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
