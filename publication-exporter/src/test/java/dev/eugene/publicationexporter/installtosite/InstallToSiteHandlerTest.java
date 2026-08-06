package dev.eugene.publicationexporter.installtosite;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.site.ManagedSiteInstaller;
import dev.eugene.publicationexporter.site.NullManagedSiteInstaller;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallToSiteHandlerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @Test
    void noApprovedSnapshotBlocksBeforeAnyInstall() {
        InstallToSiteHandler handler = new InstallToSiteHandler(
                ApprovedSnapshotWorkspace.createNull(), ManagedSiteInstaller.createNull());

        InstallToSiteResult result = handler.installToSite(IDENTITY);

        assertFalse(result.ok());
        assertEquals("No approved snapshot exists to install.", result.message());
    }

    @Test
    void approvedSnapshotIsInstalledIntoTheSite() {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        approvedSnapshotWorkspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));
        NullManagedSiteInstaller siteInstaller = new NullManagedSiteInstaller();
        InstallToSiteHandler handler = new InstallToSiteHandler(approvedSnapshotWorkspace, siteInstaller);

        InstallToSiteResult result = handler.installToSite(IDENTITY);

        assertTrue(result.ok());
        assertEquals(IDENTITY, result.identity());
        assertEquals("EN title", siteInstaller.installed().get(IDENTITY).enTitle());
    }

    @Test
    void aSecondInstallIsBlocked() {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        approvedSnapshotWorkspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash"));
        NullManagedSiteInstaller siteInstaller = new NullManagedSiteInstaller();
        InstallToSiteHandler handler = new InstallToSiteHandler(approvedSnapshotWorkspace, siteInstaller);
        handler.installToSite(IDENTITY);

        InstallToSiteResult result = handler.installToSite(IDENTITY);

        assertFalse(result.ok());
        assertEquals("A site installation already exists; replacing it is not yet supported.", result.message());
    }
}
