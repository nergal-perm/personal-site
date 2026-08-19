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
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
import dev.eugene.publicationexporter.legacy.GenerationActivationStores;
import dev.eugene.publicationexporter.legacy.MigrationCatalogStore;
import dev.eugene.publicationexporter.legacy.MigrationJournalStore;
import dev.eugene.publicationexporter.legacy.SchemaActivationCheck;
import dev.eugene.publicationexporter.legacy.SchemaActivationGuard;
import dev.eugene.publicationexporter.reference.Occurrence;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final ActivationMarkerStore activationMarkerStore;
    private final Optional<GenerationActivationStores> generationStores;

    public PrepareHandler(NoteIntake noteIntake, TranslationWorker translationWorker, CandidateWorkspace candidateWorkspace,
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, WorkflowStatusEditor workflowStatusEditor) {
        this(noteIntake, translationWorker, candidateWorkspace, approvedSnapshotWorkspace, workflowStatusEditor,
                ActivationMarkerStore.createNull());
    }

    public PrepareHandler(NoteIntake noteIntake, TranslationWorker translationWorker, CandidateWorkspace candidateWorkspace,
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace, WorkflowStatusEditor workflowStatusEditor,
            ActivationMarkerStore activationMarkerStore) {
        this.noteIntake = Objects.requireNonNull(noteIntake, "noteIntake");
        this.translationWorker = Objects.requireNonNull(translationWorker, "translationWorker");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.workflowStatusEditor = Objects.requireNonNull(workflowStatusEditor, "workflowStatusEditor");
        this.activationMarkerStore = Objects.requireNonNull(activationMarkerStore, "activationMarkerStore");
        this.generationStores = Optional.empty();
    }

    public PrepareHandler(NoteIntake noteIntake, TranslationWorker translationWorker,
            CandidateWorkspace candidateWorkspace, ApprovedSnapshotWorkspace approvedSnapshotWorkspace,
            WorkflowStatusEditor workflowStatusEditor, ActivationMarkerStore activationMarkerStore,
            MigrationJournalStore journal, MigrationCatalogStore catalog) {
        this.noteIntake = Objects.requireNonNull(noteIntake, "noteIntake");
        this.translationWorker = Objects.requireNonNull(translationWorker, "translationWorker");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace = Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.workflowStatusEditor = Objects.requireNonNull(workflowStatusEditor, "workflowStatusEditor");
        this.activationMarkerStore = Objects.requireNonNull(activationMarkerStore, "activationMarkerStore");
        this.generationStores = Optional.of(new GenerationActivationStores(journal, catalog));
    }

    public BridgeResponse prepare(
            VaultRelativePath notePath, VaultReader vaultReader, VaultAssetReader vaultAssetReader) {
        SchemaActivationCheck activation = activationCheck();
        if (activation.requiresMigration()) {
            return BridgeResponse.blocked(COMMAND, Diagnostic.blocking("workspace", activation.blockingReason()));
        }
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
                normalizedBody -> resolveAssetsThenLinks(
                        notePath, vaultReader, intake, normalizedBody, knownNotes, vaultAssetReader,
                        sourceStem, sourceId),
                position -> unclosedCommentFailure(position));
    }

    private SchemaActivationCheck activationCheck() {
        return generationStores.map(stores -> SchemaActivationGuard.check(
                        approvedSnapshotWorkspace, candidateWorkspace, activationMarkerStore,
                        stores.journal(), stores.catalog()))
                .orElseGet(() -> SchemaActivationGuard.check(
                        approvedSnapshotWorkspace, candidateWorkspace, activationMarkerStore));
    }

    private BridgeResponse resolveAssetsThenLinks(
            VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake,
            String normalizedBody, PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader,
            String sourceStem, String sourceId) {
        AssetResolutionOutcome assetOutcome;
        try {
            assetOutcome = AssetResolver.resolve(normalizedBody, vaultAssetReader);
        } catch (UncheckedIOException failure) {
            return assetResolutionLookupFailure(failure);
        }
        return assetOutcome.resolve(
                (assetResolvedBody, assets) -> LinkResolver.resolve(assetResolvedBody, knownNotes).resolve(
                        (resolvedBody, occurrences) -> prepareAfterIdentityCheck(
                                notePath, vaultReader, intake, resolvedBody, assets, knownNotes,
                                vaultAssetReader, sourceStem, sourceId, occurrences),
                        PrepareHandler::transclusionBlockedFailure),
                PrepareHandler::assetBlockedFailure);
    }

    private BridgeResponse prepareAfterIdentityCheck(
            VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake,
            String resolvedBody, List<CandidateAsset> assets, PublicNoteIndex knownNotes,
            VaultAssetReader vaultAssetReader,
            String sourceStem, String sourceId, List<LinkOccurrence> occurrences) {
        if (occurrences.isEmpty()) {
            return prepareNormalizedEssay(
                    notePath, vaultReader, intake, resolvedBody, assets, knownNotes, vaultAssetReader,
                    OccurrenceContext.empty(), sourceId, intake.body());
        }
        VaultSourceIdentityIndex identityIndex;
        try {
            identityIndex = VaultSourceIdentityIndex.from(vaultReader);
        } catch (UncheckedIOException | NoSuchElementException failure) {
            return vaultSourceIdentityLookupFailure(failure);
        }
        List<LinkOccurrence> eligibleOccurrences = eligibleOccurrences(occurrences, identityIndex);
        if (eligibleOccurrences.isEmpty()) {
            return prepareNormalizedEssay(
                    notePath, vaultReader, intake, resolvedBody, assets, knownNotes, vaultAssetReader,
                    OccurrenceContext.empty(), sourceId, intake.body());
        }
        Set<String> privateTargetStems = eligibleOccurrences.stream()
                .filter(occurrence -> occurrence.route().isEmpty())
                .map(LinkOccurrence::targetStem)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        OccurrenceContext occurrenceContext =
                new OccurrenceContext(eligibleOccurrences, Optional.of(identityIndex));
        if (privateTargetStems.isEmpty()) {
            return prepareNormalizedEssay(
                    notePath, vaultReader, intake, resolvedBody, assets, knownNotes, vaultAssetReader,
                    occurrenceContext, sourceId, intake.body());
        }
        return DirectTargetIdentityCheck.verify(sourceStem, sourceId, privateTargetStems, identityIndex)
                .resolve(
                        () -> prepareNormalizedEssay(
                                notePath, vaultReader, intake, resolvedBody, assets, knownNotes,
                                vaultAssetReader, occurrenceContext, sourceId, intake.body()),
                        PrepareHandler::directTargetIdentityBlockedFailure);
    }

    private static List<LinkOccurrence> eligibleOccurrences(
            List<LinkOccurrence> occurrences, VaultSourceIdentityIndex identityIndex) {
        // Only excludes targets with no vault identity at all (broken/typo links, which
        // scope-pins.md says stay plain text). A target that DOES exist but lacks a source ID
        // must stay eligible so it reaches DirectTargetIdentityCheck, which blocks preparation
        // for that case per SEM-01 rather than silently treating it as absent.
        return occurrences.stream()
                .filter(occurrence -> identityIndex.identityFor(occurrence.targetStem()).isPresent())
                .toList();
    }

    private BridgeResponse prepareNormalizedEssay(
            VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake,
            String normalizedBody, List<CandidateAsset> assets, PublicNoteIndex knownNotes,
            VaultAssetReader vaultAssetReader, OccurrenceContext occurrenceContext,
            String sourceId, String sourceBody) {
        ApprovedBaselineLookup approved = lookupApprovedBaseline(intake, normalizedBody);
        if (approved.failed()) {
            return approved.failureResponse();
        }
        if (approved.snapshot().isPresent()) {
            return mirrorApprovedCandidate(intake.identity(), approved.snapshot().get(), assets);
        }
        return prepareWithInstallLock(notePath, vaultReader, intake, normalizedBody, assets,
                knownNotes, vaultAssetReader, occurrenceContext, sourceId, sourceBody);
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
            OccurrenceContext occurrenceContext, String sourceId, String sourceBody) {
        ReentrantLock installLock = INSTALL_LOCKS.computeIfAbsent(intake.identity(), ignored -> new ReentrantLock());
        installLock.lock();
        try {
            return prepareAdmittedEssay(notePath, vaultReader, intake.identity(),
                    intake.sourceHash(), normalizedBody, fieldsOf(intake), intake.structuredData(), assets,
                    knownNotes, vaultAssetReader, occurrenceContext, sourceId, sourceBody);
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
            OccurrenceContext occurrenceContext, String sourceId, String sourceBody) {
        if (OccurrenceLabelMarkers.containsReservedCharacters(ruBody)) {
            recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
            return candidateFailure(
                    "Source note contains a reserved private-use-area character used internally for occurrence tracking.");
        }
        if (occurrenceContext.occurrences().size() > OccurrenceLabelMarkers.MAX_OCCURRENCES) {
            recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
            return candidateFailure("Source note has too many semantic occurrences to track ("
                    + occurrenceContext.occurrences().size() + " > "
                    + OccurrenceLabelMarkers.MAX_OCCURRENCES + ").");
        }
        OccurrenceAssignmentLookup assignmentLookup = assignOccurrences(identity, occurrenceContext);
        if (assignmentLookup.failed()) {
            return assignmentLookup.failureResponse();
        }
        List<OccurrenceAssignment.AssignedOccurrence> assignedRu = assignmentLookup.assignedOccurrences();
        String delimitedRuBody = OccurrenceLabelMarkers.delimit(
                ruBody, occurrenceContext.occurrences());
        TranslationJob job = TranslationJob.forSource(ruBody, ruFields);
        return translateCandidate(job, delimitedRuBody, ruFields).resolve(
                translation -> prepareTranslatedEssay(
                        notePath, vaultReader, identity, sourceHash,
                        delimitedRuBody, ruFields, structuredData, assets, job, translation, knownNotes, vaultAssetReader,
                        assignedRu, sourceId, sourceBody),
                failure -> {
                    recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
                    return translationFailure(failure);
                });
    }

    private OccurrenceAssignmentLookup assignOccurrences(
            PublicationIdentity identity, OccurrenceContext occurrenceContext) {
        List<LinkOccurrence> occurrences = occurrenceContext.occurrences();
        if (occurrences.isEmpty()) {
            return OccurrenceAssignmentLookup.found(List.of());
        }
        Map<String, String> targetSourceIdsByStem = buildTargetSourceIdsByStem(
                occurrences, occurrenceContext.identityIndex().orElseThrow());
        try {
            List<Occurrence> previousOccurrences = previousOccurrencesFor(identity);
            return OccurrenceAssignmentLookup.found(
                    OccurrenceAssignment.assign(occurrences, targetSourceIdsByStem, previousOccurrences));
        } catch (UncheckedIOException failure) {
            return OccurrenceAssignmentLookup.failed(candidateFailure(
                    IoFailureMessages.describe("Candidate lookup failed", failure)));
        }
    }

    private static Map<String, String> buildTargetSourceIdsByStem(
            List<LinkOccurrence> occurrences, VaultSourceIdentityIndex identityIndex) {
        Map<String, String> targetSourceIdsByStem = new LinkedHashMap<>();
        for (LinkOccurrence occurrence : occurrences) {
            targetSourceIdsByStem.put(
                    occurrence.targetStem(),
                    identityIndex.identityFor(occurrence.targetStem())
                            .flatMap(TargetIdentity::sourceId)
                            .orElseThrow());
        }
        return targetSourceIdsByStem;
    }

    private List<Occurrence> previousOccurrencesFor(PublicationIdentity identity) {
        return candidateWorkspace.read(identity)
                .map(snapshot -> snapshot.referenceMap().occurrences())
                .orElse(List.of());
    }

    private BridgeResponse prepareTranslatedEssay(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity identity, String sourceHash,
            String delimitedRuBody, List<PublicField> ruFields, String structuredData,
            List<CandidateAsset> assets, TranslationJob job,
            EnglishTranslation translation, PublicNoteIndex knownNotes, VaultAssetReader vaultAssetReader,
            List<OccurrenceAssignment.AssignedOccurrence> assignedRu,
            String sourceId, String sourceBody) {
        OccurrenceLabelMarkers.ScanResult enScan = OccurrenceLabelMarkers.scan(translation.body());
        if (translatedBodyDivergesFromAssignedOccurrences(enScan, assignedRu)) {
            recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
            return BridgeResponse.translationFailed(COMMAND, Diagnostic.blocking(
                    "candidate", "Translated candidate reordered or invented semantic occurrences."));
        }
        String ruBody = OccurrenceLabelMarkers.strip(delimitedRuBody);
        String enBody = OccurrenceLabelMarkers.strip(translation.body());
        List<PublicField> enFields = translation.fields();
        List<Occurrence> occurrences = occurrencesWithEnglishLabels(assignedRu, enScan.spans());
        if (anyOccurrenceLabelIsBlank(occurrences)) {
            recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
            return BridgeResponse.translationFailed(COMMAND, Diagnostic.blocking(
                    "candidate", "Translated candidate produced a blank label for a semantic occurrence."));
        }

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
                            identity, sourceId, ruBody, enBody, ruFields, enFields,
                            structuredData, occurrences, sourceBody);
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

    private static boolean anyOccurrenceLabelIsBlank(List<Occurrence> occurrences) {
        return occurrences.stream()
                .anyMatch(occurrence -> occurrence.ruLabel().isBlank() || occurrence.enLabel().isBlank());
    }

    private static boolean translatedBodyDivergesFromAssignedOccurrences(
            OccurrenceLabelMarkers.ScanResult translatedScan,
            List<OccurrenceAssignment.AssignedOccurrence> assignedRussianOccurrences) {
        if (translatedScan.malformed()) {
            return true;
        }
        Set<Integer> assignedIndices = new LinkedHashSet<>();
        for (int i = 0; i < assignedRussianOccurrences.size(); i++) {
            assignedIndices.add(i);
        }
        return !translatedScan.spans().keySet().equals(assignedIndices);
    }

    private static List<Occurrence> occurrencesWithEnglishLabels(
            List<OccurrenceAssignment.AssignedOccurrence> assignedRu, Map<Integer, String> enLabels) {
        List<Occurrence> occurrences = new ArrayList<>();
        for (int i = 0; i < assignedRu.size(); i++) {
            OccurrenceAssignment.AssignedOccurrence ru = assignedRu.get(i);
            occurrences.add(new Occurrence(
                    ru.id(), ru.order(), ru.targetSourceId(), ru.ruLabel(), enLabels.get(i)));
        }
        return List.copyOf(occurrences);
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
                normalizedBody -> AssetResolver.resolve(normalizedBody, vaultAssetReader).resolve(
                        (assetResolvedBody, ignoredAssets) -> LinkResolver.resolve(
                                assetResolvedBody, knownNotes).resolve(
                                (resolvedBody, ignoredOccurrences) ->
                                        sourceFingerprintMatches(job, resolvedBody, fieldsOf(current))
                                                && expectedStructuredData.equals(current.structuredData())
                                                ? SourceFreshnessOutcome.matches(current.sourceHash())
                                                : SourceFreshnessOutcome.stale(),
                                SourceFreshnessOutcome::blockedTransclusion),
                        SourceFreshnessOutcome::assetBlocked),
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
            PublicationIdentity identity, String sourceId, String ruBody, String enBody,
            List<PublicField> ruFields, List<PublicField> enFields, String structuredData,
            List<Occurrence> occurrences, String sourceBody) {
        return ReferenceMap.of(
                identity,
                sourceId,
                ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody),
                ContentHash.sha256Hex(PublicFieldsCodec.write(ruFields)),
                ContentHash.sha256Hex(PublicFieldsCodec.write(enFields)),
                ContentHash.sha256Hex(structuredData),
                occurrences,
                ContentHash.sha256Hex(sourceBody));
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

    private record OccurrenceAssignmentLookup(
            List<OccurrenceAssignment.AssignedOccurrence> assignedOccurrences,
            Optional<BridgeResponse> failure) {

        private OccurrenceAssignmentLookup {
            assignedOccurrences = List.copyOf(assignedOccurrences);
        }

        private static OccurrenceAssignmentLookup found(
                List<OccurrenceAssignment.AssignedOccurrence> assignedOccurrences) {
            return new OccurrenceAssignmentLookup(assignedOccurrences, Optional.empty());
        }

        private static OccurrenceAssignmentLookup failed(BridgeResponse failure) {
            return new OccurrenceAssignmentLookup(List.of(), Optional.of(failure));
        }

        private boolean failed() {
            return failure.isPresent();
        }

        private BridgeResponse failureResponse() {
            return failure.orElseThrow();
        }
    }

}
