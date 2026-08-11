package dev.eugene.publicationexporter;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotIntegrityException;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspaceKindCollisionException;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.release.ReleaseAlreadyExistsException;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import dev.eugene.publicationexporter.release.ReleaseProvenance;
import dev.eugene.publicationexporter.site.ManagedSiteInstaller;
import dev.eugene.publicationexporter.site.ManagedSiteKindCollisionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reproduces prob-20260811-0b8b9f2d: every real filesystem adapter keys its on-disk path by
 * (publicCollection, publicId) only, so a second kind admitted into the same collection under
 * the same publicId could previously overwrite the first kind's candidate, approved snapshot,
 * release output, or site content. Each adapter here proves an essay's durable state survives
 * an attempted note install at the identical (collection, publicId) address.
 */
class CrossKindAddressCollisionAcceptanceTest {

    @TempDir
    Path root;

    private static final PublicationIdentity ESSAY = PublicationIdentity.of("blog", "essay", "same-slug");
    private static final PublicationIdentity NOTE = PublicationIdentity.of("blog", "note", "same-slug");

    @Test
    void candidateWorkspaceFailsClosedAndPreservesTheFirstKindsCandidate() throws Exception {
        CandidateWorkspace workspace = CandidateWorkspace.create(root);
        workspace.install(ESSAY, candidateSnapshot(ESSAY, "essay RU"), List.of());

        assertThrows(CandidateWorkspaceKindCollisionException.class,
                () -> workspace.install(NOTE, candidateSnapshot(NOTE, "note RU"), List.of()));

        assertEquals("essay RU", Files.readString(root.resolve("blog/same-slug/candidate/ru.md")));
    }

    @Test
    void approvedSnapshotWorkspaceFailsClosedAndPreservesTheFirstKindsApprovedSnapshot() throws Exception {
        ApprovedSnapshotWorkspace workspace = ApprovedSnapshotWorkspace.create(root);
        installApproved(workspace, ESSAY, "essay RU");

        assertThrows(ApprovedSnapshotIntegrityException.class, () -> installApproved(workspace, NOTE, "note RU"));

        assertEquals("essay RU", Files.readString(root.resolve("blog/same-slug/approved/ru.md")));
    }

    @Test
    void releaseOutputStoreFailsClosedAndPreservesTheFirstKindsRelease() throws Exception {
        ReleaseOutputStore store = ReleaseOutputStore.create(root);
        store.install(ESSAY, "essay RU", "essay EN", ReleaseProvenance.of(ESSAY, "aH", "bH", "cH", "dH"));

        assertThrows(ReleaseAlreadyExistsException.class, () ->
                store.install(NOTE, "note RU", "note EN", ReleaseProvenance.of(NOTE, "eH", "fH", "gH", "hH")));

        assertEquals("essay RU", Files.readString(root.resolve("blog/same-slug/release/ru.md")));
    }

    @Test
    void managedSiteInstallerFailsClosedAndPreservesTheFirstKindsSiteContent() throws Exception {
        ManagedSiteInstaller installer = ManagedSiteInstaller.create(root);
        installer.install(ESSAY, candidateSnapshot(ESSAY, "essay RU body"));

        assertThrows(ManagedSiteKindCollisionException.class,
                () -> installer.install(NOTE, candidateSnapshot(NOTE, "note RU body")));

        String installed = Files.readString(root.resolve("src/content/blog/ru/same-slug.md"));
        assertEquals(true, installed.contains("essay RU body"));
    }

    private static void installApproved(ApprovedSnapshotWorkspace workspace, PublicationIdentity identity, String ruBody) {
        workspace.install(identity, ruBody, "en", "ru-title", "en-title", "ru-desc", "en-desc",
                referenceMap(identity, ruBody));
    }

    private static CandidateSnapshot candidateSnapshot(PublicationIdentity identity, String ruBody) {
        return CandidateSnapshot.of(ruBody, "en", "ru-title", "en-title", "ru-desc", "en-desc",
                referenceMap(identity, ruBody));
    }

    private static ReferenceMap referenceMap(PublicationIdentity identity, String ruBody) {
        return ReferenceMap.empty(identity,
                ContentHash.sha256Hex(ruBody), ContentHash.sha256Hex("en"),
                ContentHash.sha256Hex("ru-title"), ContentHash.sha256Hex("en-title"),
                ContentHash.sha256Hex("ru-desc"), ContentHash.sha256Hex("en-desc"));
    }
}
