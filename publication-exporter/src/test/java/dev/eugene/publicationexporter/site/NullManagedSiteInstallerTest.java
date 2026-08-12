package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.LegacyCandidateSnapshotFixture;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NullManagedSiteInstallerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final CandidateSnapshot SNAPSHOT = LegacyCandidateSnapshotFixture.of(
            "RU body", "EN body", "RU title", "EN title", "RU description.", "EN description.",
            ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));

    @Test
    void installRecordsTheInstalledSnapshot() {
        NullManagedSiteInstaller installer = new NullManagedSiteInstaller();

        installer.install(IDENTITY, SNAPSHOT);

        assertEquals(SNAPSHOT, installer.installed().get(IDENTITY));
    }

    @Test
    void secondInstallReplacesThePriorGeneration() {
        NullManagedSiteInstaller installer = new NullManagedSiteInstaller();
        installer.install(IDENTITY, candidateSnapshot("Old RU", "Old EN"));

        installer.install(IDENTITY, candidateSnapshot("New RU", "New EN"));

        CandidateSnapshot installed = installer.installed().get(IDENTITY);
        assertEquals("New RU", installed.ruBody());
        assertEquals("New EN", installed.enBody());
    }

    @Test
    void installRejectsNullIdentity() {
        NullManagedSiteInstaller installer = new NullManagedSiteInstaller();

        assertThrows(NullPointerException.class, () -> installer.install(null, SNAPSHOT));
    }

    @Test
    void installRejectsNullSnapshot() {
        NullManagedSiteInstaller installer = new NullManagedSiteInstaller();

        assertThrows(NullPointerException.class, () -> installer.install(IDENTITY, null));
    }

    @Test
    void interfaceFactoryReturnsAFreshEmptyInstaller() {
        ManagedSiteInstaller installer = ManagedSiteInstaller.createNull();
        assertTrue(((NullManagedSiteInstaller) installer).installed().isEmpty());

        installer.install(IDENTITY, SNAPSHOT);
    }

    private static CandidateSnapshot candidateSnapshot(String ruBody, String enBody) {
        return LegacyCandidateSnapshotFixture.of(
                ruBody,
                enBody,
                "RU title",
                "EN title",
                "RU description.",
                "EN description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));
    }
}
