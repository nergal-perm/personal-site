package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NullManagedSiteInstallerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final CandidateSnapshot SNAPSHOT = CandidateSnapshot.of(
            "RU body", "EN body", "RU title", "EN title", "RU description.", "EN description.",
            ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));

    @Test
    void installRecordsTheInstalledSnapshot() {
        NullManagedSiteInstaller installer = new NullManagedSiteInstaller();

        installer.install(IDENTITY, SNAPSHOT);

        assertEquals(SNAPSHOT, installer.installed().get(IDENTITY));
    }

    @Test
    void aSecondInstallForTheSameIdentityThrows() {
        NullManagedSiteInstaller installer = new NullManagedSiteInstaller();
        installer.install(IDENTITY, SNAPSHOT);

        assertThrows(SiteAlreadyInstalledException.class, () -> installer.install(IDENTITY, SNAPSHOT));
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
}
