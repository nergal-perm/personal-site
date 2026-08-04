package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullCandidateWorkspaceTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void installedIsEmptyBeforeAnyCall() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();

        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void installedRecordsExactlyWhatWasPassed() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash");

        workspace.install(IDENTITY, "RU body", "EN body", referenceMap);

        assertEquals(1, workspace.installed().size());
        NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);
        assertEquals(IDENTITY, installed.identity());
        assertEquals("RU body", installed.ruBody());
        assertEquals("EN body", installed.enBody());
        assertEquals(referenceMap, installed.referenceMap());
    }

    @Test
    void interfaceFactoryReturnsAFreshEmptyWorkspace() {
        CandidateWorkspace workspace = CandidateWorkspace.createNull();

        assertTrue(((NullCandidateWorkspace) workspace).installed().isEmpty());
    }
}
