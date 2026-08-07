package dev.eugene.publicationexporter.installtosite;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidatePaths;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.site.ManagedSiteInstaller;
import dev.eugene.publicationexporter.site.NullManagedSiteInstaller;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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
    void aSecondInstallReplacesTheManagedGeneration() {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        approvedSnapshotWorkspace.install(IDENTITY, "Old RU body", "Old EN body", "Old RU title", "Old EN title",
                "Old RU description.", "Old EN description.",
                ReferenceMap.empty(IDENTITY, "old-ru-hash", "old-en-hash", "old-ru-title-hash", "old-en-title-hash", "old-ru-description-hash", "old-en-description-hash"));
        NullManagedSiteInstaller siteInstaller = new NullManagedSiteInstaller();
        InstallToSiteHandler handler = new InstallToSiteHandler(approvedSnapshotWorkspace, siteInstaller);
        handler.installToSite(IDENTITY);

        approvedSnapshotWorkspace.install(IDENTITY, "New RU body", "New EN body", "New RU title", "New EN title",
                "New RU description.", "New EN description.",
                ReferenceMap.empty(IDENTITY, "new-ru-hash", "new-en-hash", "new-ru-title-hash", "new-en-title-hash", "new-ru-description-hash", "new-en-description-hash"));

        InstallToSiteResult result = handler.installToSite(IDENTITY);

        assertTrue(result.ok());
        assertEquals("New EN title", siteInstaller.installed().get(IDENTITY).enTitle());
    }

    @Test
    void approvedSnapshotDriftBetweenPlanAndCommitBlocksWithoutInstalling() {
        NullApprovedSnapshotWorkspace backingWorkspace = new NullApprovedSnapshotWorkspace();
        backingWorkspace.install(IDENTITY, "RU body", "EN body", "RU title", "EN title",
                "RU description.", "EN description.",
                ReferenceMap.empty(IDENTITY, "ru-hash", "en-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = workspaceThatChangesOnSecondRead(backingWorkspace);
        NullManagedSiteInstaller siteInstaller = new NullManagedSiteInstaller();
        InstallToSiteHandler handler = new InstallToSiteHandler(approvedSnapshotWorkspace, siteInstaller);

        InstallToSiteResult result = handler.installToSite(IDENTITY);

        assertFalse(result.ok());
        assertEquals("Approved snapshot changed since release was planned; site installation was not attempted.",
                result.message());
        assertTrue(siteInstaller.installed().isEmpty());
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

    private static ApprovedSnapshotWorkspace workspaceThatChangesOnSecondRead(
            NullApprovedSnapshotWorkspace backingWorkspace) {
        return new ApprovedSnapshotWorkspace() {
            private int readCount;

            @Override
            public void install(PublicationIdentity identity, String ruBody, String enBody,
                    String ruTitle, String enTitle, String ruDescription, String enDescription,
                    ReferenceMap referenceMap) {
                backingWorkspace.install(identity, ruBody, enBody, ruTitle, enTitle,
                        ruDescription, enDescription, referenceMap);
            }

            @Override
            public Optional<CandidatePaths> find(PublicationIdentity identity) {
                return backingWorkspace.find(identity);
            }

            @Override
            public Optional<CandidateSnapshot> read(PublicationIdentity identity) {
                readCount++;
                if (readCount == 2) {
                    install(identity, "Changed RU body", "Changed EN body", "Changed RU title", "Changed EN title",
                            "Changed RU description.", "Changed EN description.",
                            ReferenceMap.empty(identity, "changed-ru-hash", "changed-en-hash",
                                    "changed-ru-title-hash", "changed-en-title-hash",
                                    "changed-ru-description-hash", "changed-en-description-hash"));
                }
                return backingWorkspace.read(identity);
            }
        };
    }
}
