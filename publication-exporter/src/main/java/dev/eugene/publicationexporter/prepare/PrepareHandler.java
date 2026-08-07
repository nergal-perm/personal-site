package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.IoFailureMessages;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
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
import java.util.Objects;

public final class PrepareHandler {

    private static final String COMMAND = "prepare";

    private final TranslationWorker translationWorker;
    private final CandidateWorkspace candidateWorkspace;

    public PrepareHandler(TranslationWorker translationWorker, CandidateWorkspace candidateWorkspace) {
        this.translationWorker = Objects.requireNonNull(translationWorker, "translationWorker");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
    }

    public BridgeResponse prepare(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        return prepareAdmittedEssay(intake.identity(), intake.body(), intake.title(), intake.description());
    }

    private BridgeResponse prepareAdmittedEssay(
            PublicationIdentity identity, String ruBody, String ruTitle, String ruDescription) {
        TranslationResult translation;
        TranslationJob job = TranslationJob.forSource(ruBody, ruTitle, ruDescription);
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
        if (enBody.isBlank()) {
            return BridgeResponse.translationFailed(COMMAND,
                    Diagnostic.blocking("candidate", "Translation worker produced a blank candidate."));
        }
        String enTitle = translation.enTitle();
        if (enTitle.isBlank()) {
            return BridgeResponse.translationFailed(COMMAND,
                    Diagnostic.blocking("candidate", "Translation worker produced a blank title."));
        }
        String enDescription = translation.enDescription();
        if (enDescription.isBlank()) {
            return BridgeResponse.translationFailed(COMMAND,
                    Diagnostic.blocking("candidate", "Translation worker produced a blank description."));
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

    private static BridgeResponse candidateFailure(String message) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", message));
    }

}
