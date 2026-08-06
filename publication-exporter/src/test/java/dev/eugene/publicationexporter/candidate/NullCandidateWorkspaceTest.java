package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import java.util.Optional;

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
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash");

        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        assertEquals(1, workspace.installed().size());
        NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);
        assertEquals(IDENTITY, installed.identity());
        assertEquals("RU body", installed.ruBody());
        assertEquals("EN body", installed.enBody());
        assertEquals("RU title", installed.ruTitle());
        assertEquals("EN title", installed.enTitle());
        assertEquals("RU description.", installed.ruDescription());
        assertEquals("EN description.", installed.enDescription());
        assertEquals(referenceMap, installed.referenceMap());
    }

    @Test
    void interfaceFactoryReturnsAFreshEmptyWorkspace() {
        CandidateWorkspace workspace = CandidateWorkspace.createNull();

        assertTrue(((NullCandidateWorkspace) workspace).installed().isEmpty());
    }

    @Test
    void findIsAbsentBeforeAnyInstall() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();

        assertEquals(Optional.empty(), workspace.find(IDENTITY));
    }

    @Test
    void findReturnsPathsEndingInRuMdAndEnMdAfterInstall() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        workspace.install(IDENTITY, "RU body", "EN body", "Title", "EN Title",
                "Description.", "EN Description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));

        Optional<CandidatePaths> found = workspace.find(IDENTITY);

        assertTrue(found.isPresent());
        assertEquals("ru.md", found.get().ruPath().getFileName().toString());
        assertEquals("en.md", found.get().enPath().getFileName().toString());
    }

    @Test
    void findIsAbsentForADifferentIdentity() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        workspace.install(IDENTITY, "RU body", "EN body", "Title", "EN Title",
                "Description.", "EN Description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");

        assertEquals(Optional.empty(), workspace.find(otherIdentity));
    }

    @Test
    void readIsAbsentBeforeAnyInstall() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void readReturnsTheInstalledBodiesAndReferenceMap() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash");
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Optional<CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU body", read.get().ruBody());
        assertEquals("EN body", read.get().enBody());
        assertEquals("RU title", read.get().ruTitle());
        assertEquals("EN title", read.get().enTitle());
        assertEquals("RU description.", read.get().ruDescription());
        assertEquals("EN description.", read.get().enDescription());
        assertEquals(referenceMap, read.get().referenceMap());
    }

    @Test
    void readIsAbsentForADifferentIdentity() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        workspace.install(IDENTITY, "RU body", "EN body", "Title", "EN Title",
                "Description.", "EN Description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");

        assertEquals(Optional.empty(), workspace.read(otherIdentity));
    }

    @Test
    void readIsAbsentWhenTheReferenceMapCarriesADifferentIdentity() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");
        workspace.install(IDENTITY, "RU body", "EN body", "Title", "EN Title",
                "Description.", "EN Description.", ReferenceMap.empty(otherIdentity, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void readReturnsTheInstalledTitleAndDescription() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash");
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Optional<CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU title", read.get().ruTitle());
        assertEquals("EN title", read.get().enTitle());
        assertEquals("RU description.", read.get().ruDescription());
        assertEquals("EN description.", read.get().enDescription());
    }
}
