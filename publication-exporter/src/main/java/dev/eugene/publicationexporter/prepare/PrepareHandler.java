package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.admission.EssayAdmission;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.note.Frontmatter;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.translation.TranslationResult;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.NoSuchElementException;
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
        if (!notePath.isWithinVault()) {
            return blockedForVaultEscape();
        }
        if (!notePath.hasMarkdownExtension()) {
            return blockedForNonMarkdownNote();
        }
        if (!vaultReader.exists(notePath)) {
            return blockedForMissingNote();
        }
        return prepareExistingNote(notePath, vaultReader);
    }

    private BridgeResponse prepareExistingNote(VaultRelativePath notePath, VaultReader vaultReader) {
        try {
            Frontmatter frontmatter = Frontmatter.parse(vaultReader.readSource(notePath));
            EssayAdmission.Result admission = new EssayAdmission().admit(frontmatter);
            if (!admission.accepted()) {
                return BridgeResponse.blocked(COMMAND, admission.diagnostics());
            }
            return prepareAdmittedEssay(admission.identity(), frontmatter.body());
        } catch (NoSuchElementException | UncheckedIOException failure) {
            return blockedForMissingNote();
        }
    }

    private BridgeResponse prepareAdmittedEssay(PublicationIdentity identity, String ruBody) {
        TranslationResult translation = translationWorker.translate(ruBody);
        if (!translation.succeeded()) {
            return BridgeResponse.translationFailed(COMMAND,
                    Diagnostic.blocking("candidate", translation.failureReason()));
        }
        String enBody = translation.enBody();
        ReferenceMap referenceMap = ReferenceMap.empty(identity, sha256Hex(ruBody), sha256Hex(enBody));
        candidateWorkspace.install(identity, ruBody, enBody, referenceMap);
        return BridgeResponse.prepared(COMMAND, identity);
    }

    private BridgeResponse blockedForVaultEscape() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("note", "Note path escapes the vault root."));
    }

    private BridgeResponse blockedForMissingNote() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("note", "Note was not found in the vault."));
    }

    private BridgeResponse blockedForNonMarkdownNote() {
        return BridgeResponse.blocked(COMMAND,
                Diagnostic.blocking("note", "Note path must name a Markdown file."));
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", impossible);
        }
    }
}
