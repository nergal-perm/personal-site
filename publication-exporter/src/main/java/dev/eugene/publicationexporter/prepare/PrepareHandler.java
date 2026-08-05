package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspaceConfinementException;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.reference.ReferenceMap;
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
        return prepareAdmittedEssay(intake.identity(), intake.body());
    }

    private BridgeResponse prepareAdmittedEssay(PublicationIdentity identity, String ruBody) {
        TranslationResult translation;
        try {
            translation = translationWorker.translate(ruBody);
        } catch (UncheckedIOException failure) {
            return candidateFailure(ioFailureMessage("Translation worker I/O failed", failure));
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
        ReferenceMap referenceMap = ReferenceMap.empty(
                identity, ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex(enBody));
        try {
            candidateWorkspace.install(identity, ruBody, enBody, referenceMap);
        } catch (UncheckedIOException failure) {
            return candidateFailure(ioFailureMessage("Candidate installation failed", failure));
        } catch (CandidateWorkspaceConfinementException failure) {
            return candidateFailure("Candidate installation failed: " + failure.getMessage());
        }
        return BridgeResponse.prepared(COMMAND, identity);
    }

    private static BridgeResponse candidateFailure(String message) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", message));
    }

    private static String ioFailureMessage(String operation, UncheckedIOException failure) {
        String detail = failure.getCause().getMessage();
        return detail == null || detail.isBlank() ? operation + "." : operation + ": " + detail;
    }

}
