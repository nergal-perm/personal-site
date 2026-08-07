package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
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
import dev.eugene.publicationexporter.translation.TranslationJob;
import dev.eugene.publicationexporter.translation.TranslationResult;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PrepareHandler {

    private static final String COMMAND = "prepare";

    private final TranslationWorker translationWorker;
    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;

    public PrepareHandler(TranslationWorker translationWorker, CandidateWorkspace candidateWorkspace,
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
        this.translationWorker = Objects.requireNonNull(translationWorker, "translationWorker");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
    }

    public BridgeResponse prepare(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        Optional<CandidateSnapshot> approved = approvedSnapshotWorkspace.read(intake.identity());
        if (approved.isPresent()) {
            RussianDiff diff = RussianDiff.betweenBodies(approved.get().ruBody(), intake.body());
            if (diff.isEmpty()) {
                return BridgeResponse.prepared(COMMAND, intake.identity());
            }
        }
        return prepareAdmittedEssay(intake.identity(), intake.body(), intake.title(), intake.description());
    }

    private BridgeResponse prepareAdmittedEssay(
            PublicationIdentity identity, String ruBody, String ruTitle, String ruDescription) {
        TranslationJob job = TranslationJob.forSource(ruBody, ruTitle, ruDescription);
        TranslationResult translation;
        try {
            translation = translationWorker.translate(job, ruBody, ruTitle, ruDescription);
        } catch (UncheckedIOException failure) {
            return candidateFailure(IoFailureMessages.describe("Translation worker I/O failed", failure));
        }
        if (!translation.succeeded()) {
            return BridgeResponse.translationFailed(COMMAND,
                    Diagnostic.blocking("candidate", translation.failureReason()));
        }
        String enBody = translation.enBody();
        String enTitle = translation.enTitle();
        String enDescription = translation.enDescription();

        EnglishCandidateValidator.Result validation =
                EnglishCandidateValidator.validate(ruBody, enBody, enTitle, enDescription);
        if (!validation.valid()) {
            return BridgeResponse.translationFailed(COMMAND, blockingDiagnostics(validation.diagnostics()));
        }
        ReferenceMap referenceMap = ReferenceMap.empty(
                identity,
                ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody),
                ContentHash.sha256Hex(ruTitle), ContentHash.sha256Hex(enTitle),
                ContentHash.sha256Hex(ruDescription), ContentHash.sha256Hex(enDescription));
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

    private static Diagnostic blockingDiagnostics(List<String> diagnostics) {
        return Diagnostic.blocking("candidate", String.join(" ", diagnostics));
    }

    private static BridgeResponse candidateFailure(String message) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", message));
    }

}
