package dev.eugene.publicationexporter.installtosite;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.site.ManagedSiteInstaller;
import dev.eugene.publicationexporter.site.NullManagedSiteInstaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallToSiteHandlerTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");

    @TempDir
    Path siteRoot;

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
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));
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
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));
        NullManagedSiteInstaller siteInstaller = new NullManagedSiteInstaller();
        InstallToSiteHandler handler = new InstallToSiteHandler(approvedSnapshotWorkspace, siteInstaller);
        handler.installToSite(IDENTITY);

        InstallToSiteResult result = handler.installToSite(IDENTITY);

        assertFalse(result.ok());
        assertEquals("A site installation already exists; replacing it is not yet supported.", result.message());
    }

    @Test
    void unsafeManagedTreeEntryProducesABlockedResultAndRollsBackLocaleFiles() throws Exception {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        approvedSnapshotWorkspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash",
                        "ru-title-hash", "en-title-hash",
                        "ru-description-hash", "en-description-hash"));
        Path pagesRoot = siteRoot.resolve("src/data/pages");
        Files.createDirectories(pagesRoot);
        Path symlinkTarget = siteRoot.resolve("unsafe-target.txt");
        Files.writeString(symlinkTarget, "unsafe");
        Files.createSymbolicLink(pagesRoot.resolve("unsafe-link"), symlinkTarget);
        InstallToSiteHandler handler = new InstallToSiteHandler(
                approvedSnapshotWorkspace, ManagedSiteInstaller.create(siteRoot));

        InstallToSiteResult result = handler.installToSite(IDENTITY);

        assertFalse(result.ok());
        assertTrue(result.message().contains("unsafe managed content"));
        assertTrue(result.message().contains("symlink"));
        assertFalse(Files.exists(siteRoot.resolve("src/content/blog/ru/my-essay.md")));
        assertFalse(Files.exists(siteRoot.resolve("src/content/blog/en/my-essay.md")));
    }
}
