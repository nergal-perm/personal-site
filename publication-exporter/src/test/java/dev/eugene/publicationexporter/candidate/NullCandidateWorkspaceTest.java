package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullCandidateWorkspaceTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void allIdentitiesReturnsEveryInstalledIdentitySorted() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PublicationIdentity zebra = PublicationIdentity.of("blog", "essay", "zebra");
        PublicationIdentity apple = PublicationIdentity.of("blog", "essay", "apple");
        workspace.install(zebra, snapshot("RU", "EN", "Title", "EN Title", "Description", "EN Description",
                ReferenceMap.empty(zebra, "ru", "en", "ru-fields", "en-fields", "structured")), List.of());
        workspace.install(apple, snapshot("RU", "EN", "Title", "EN Title", "Description", "EN Description",
                ReferenceMap.empty(apple, "ru", "en", "ru-fields", "en-fields", "structured")), List.of());

        assertEquals(List.of(apple, zebra), workspace.allIdentities());
    }

    @Test
    void allIdentitiesReturnsOneEntryWhenTheSameIdentityIsInstalledTwice() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        CandidateSnapshot snapshot = snapshot("RU", "EN", "Title", "EN Title", "Description", "EN Description",
                ReferenceMap.empty(IDENTITY, "ru", "en", "ru-fields", "en-fields", "structured"));
        workspace.install(IDENTITY, snapshot, List.of());
        workspace.install(IDENTITY, snapshot, List.of());

        assertEquals(List.of(IDENTITY), workspace.allIdentities());
    }

    @Test
    void allIdentitiesIsEmptyForAFreshWorkspace() {
        assertEquals(List.of(), new NullCandidateWorkspace().allIdentities());
    }

    @Test
    void installedIsEmptyBeforeAnyCall() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();

        assertTrue(workspace.installed().isEmpty());
    }

    @Test
    void installedRecordsExactlyWhatWasPassed() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "en-description-hash");

        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        assertEquals(1, workspace.installed().size());
        NullCandidateWorkspace.InstalledCandidate installed = workspace.installed().get(0);
        assertEquals(IDENTITY, installed.identity());
        assertEquals("RU body", installed.ruBody());
        assertEquals("EN body", installed.enBody());
        assertEquals(List.of(PublicField.of("title", "RU title"), PublicField.of("description", "RU description.")),
                installed.ruFields());
        assertEquals(List.of(PublicField.of("title", "EN title"), PublicField.of("description", "EN description.")),
                installed.enFields());
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
                "Description.", "EN Description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "en-description-hash"));

        Optional<CandidatePaths> found = workspace.find(IDENTITY);

        assertTrue(found.isPresent());
        assertEquals("ru.md", found.get().ruPath().getFileName().toString());
        assertEquals("en.md", found.get().enPath().getFileName().toString());
    }

    @Test
    void findIsAbsentForADifferentIdentity() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        workspace.install(IDENTITY, "RU body", "EN body", "Title", "EN Title",
                "Description.", "EN Description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "en-description-hash"));
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
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "en-description-hash");
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Optional<CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU body", read.get().ruBody());
        assertEquals("EN body", read.get().enBody());
        assertEquals("RU title", PublicField.value(read.get().ruFields(), "title").orElseThrow());
        assertEquals("EN title", PublicField.value(read.get().enFields(), "title").orElseThrow());
        assertEquals("RU description.", PublicField.value(read.get().ruFields(), "description").orElseThrow());
        assertEquals("EN description.", PublicField.value(read.get().enFields(), "description").orElseThrow());
        assertEquals(referenceMap, read.get().referenceMap());
    }

    @Test
    void readIsAbsentForADifferentIdentity() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        workspace.install(IDENTITY, "RU body", "EN body", "Title", "EN Title",
                "Description.", "EN Description.", ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "en-description-hash"));
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");

        assertEquals(Optional.empty(), workspace.read(otherIdentity));
    }

    @Test
    void readIsAbsentWhenTheReferenceMapCarriesADifferentIdentity() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        PublicationIdentity otherIdentity = PublicationIdentity.of("blog", "essay", "other-essay");
        workspace.install(IDENTITY, "RU body", "EN body", "Title", "EN Title",
                "Description.", "EN Description.", ReferenceMap.empty(otherIdentity, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "en-description-hash"));

        assertEquals(Optional.empty(), workspace.read(IDENTITY));
    }

    @Test
    void readReturnsTheInstalledTitleAndDescription() {
        NullCandidateWorkspace workspace = new NullCandidateWorkspace();
        ReferenceMap referenceMap = ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "en-description-hash");
        workspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.", referenceMap);

        Optional<CandidateSnapshot> read = workspace.read(IDENTITY);

        assertTrue(read.isPresent());
        assertEquals("RU title", PublicField.value(read.get().ruFields(), "title").orElseThrow());
        assertEquals("EN title", PublicField.value(read.get().enFields(), "title").orElseThrow());
        assertEquals("RU description.", PublicField.value(read.get().ruFields(), "description").orElseThrow());
        assertEquals("EN description.", PublicField.value(read.get().enFields(), "description").orElseThrow());
    }

    private static CandidateSnapshot snapshot(String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription,
            ReferenceMap referenceMap) {
        return CandidateSnapshot.of(ruBody, enBody,
                List.of(PublicField.of("title", ruTitle), PublicField.of("description", ruDescription)),
                List.of(PublicField.of("title", enTitle), PublicField.of("description", enDescription)),
                "", referenceMap);
    }
}
