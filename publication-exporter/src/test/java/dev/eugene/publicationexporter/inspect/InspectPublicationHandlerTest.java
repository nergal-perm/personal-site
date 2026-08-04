package dev.eugene.publicationexporter.inspect;

import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectPublicationHandlerTest {

    private final InspectPublicationHandler handler = new InspectPublicationHandler();

    @Test
    void unsafePathIsBlockedWithEscapeDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        BridgeResponse response = handler.inspect(
                VaultRelativePath.of("../../etc/passwd.md"), vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals(1, response.diagnostics().size());
        assertEquals("Note path escapes the vault root.",
                response.diagnostics().get(0).message());
    }

    @Test
    void absentNoteIsBlockedWithNotFoundDiagnostic() {
        VaultReader vaultReader = VaultReader.createNull();
        BridgeResponse response = handler.inspect(
                VaultRelativePath.of("blog/does-not-exist.md"), vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals(1, response.diagnostics().size());
        assertEquals("Note was not found in the vault.",
                response.diagnostics().get(0).message());
    }

    private static final String VALID_ESSAY = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            sourceId: 8f2c-my-essay
            ---
            """;

    @Test
    void validEssayIsAcceptedWithAllStatesAbsent() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ESSAY));

        BridgeResponse response = handler.inspect(path, vaultReader);

        assertTrue(response.ok());
        assertEquals("not_prepared", response.status());
        assertEquals("blog", response.identity().publicCollection());
        assertEquals("essay", response.identity().publicContentType());
        assertEquals("my-essay", response.identity().publicId());
        assertEquals("absent", response.candidateState());
        assertEquals("absent", response.approvedSnapshotState());
        assertEquals("absent", response.semanticReferenceState());
        assertEquals("absent", response.releaseState());
        assertEquals(0, response.diagnostics().size());
    }

    @Test
    void essayMissingSourceIdIsBlocked() {
        String essayWithoutSourceId = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                ---
                """;
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, essayWithoutSourceId));

        BridgeResponse response = handler.inspect(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("metadata_blocked", response.status());
        assertEquals("sourceId", response.diagnostics().get(0).field());
    }

    @Test
    void essayWithUnsupportedContentTypeIsBlocked() {
        String claimNote = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: claim
                publicId: my-claim
                sourceId: 8f2c-my-claim
                ---
                """;
        VaultRelativePath path = VaultRelativePath.of("blog/my-claim.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, claimNote));

        BridgeResponse response = handler.inspect(path, vaultReader);

        assertFalse(response.ok());
        assertEquals("publicContentType", response.diagnostics().get(0).field());
    }
}
