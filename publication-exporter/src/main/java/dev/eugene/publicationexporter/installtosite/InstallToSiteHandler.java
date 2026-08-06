package dev.eugene.publicationexporter.installtosite;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceConfinementException;
import dev.eugene.publicationexporter.bridge.IoFailureMessages;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.site.ManagedSiteInstaller;
import dev.eugene.publicationexporter.site.SiteAlreadyInstalledException;

import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;

public final class InstallToSiteHandler {

    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final ManagedSiteInstaller managedSiteInstaller;

    public InstallToSiteHandler(ApprovedSnapshotWorkspace approvedSnapshotWorkspace,
            ManagedSiteInstaller managedSiteInstaller) {
        this.approvedSnapshotWorkspace = Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.managedSiteInstaller = Objects.requireNonNull(managedSiteInstaller, "managedSiteInstaller");
    }

    public InstallToSiteResult installToSite(PublicationIdentity identity) {
        Optional<CandidateSnapshot> approved;
        try {
            approved = approvedSnapshotWorkspace.read(identity);
        } catch (UncheckedIOException failure) {
            return InstallToSiteResult.blocked(IoFailureMessages.describe("Approved snapshot lookup failed", failure));
        } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
            return InstallToSiteResult.blocked("Approved snapshot lookup failed: " + failure.getMessage());
        }
        if (approved.isEmpty()) {
            return InstallToSiteResult.blocked("No approved snapshot exists to install.");
        }
        return installApprovedSnapshot(identity, approved.get());
    }

    private InstallToSiteResult installApprovedSnapshot(PublicationIdentity identity, CandidateSnapshot approved) {
        try {
            managedSiteInstaller.install(identity, approved);
        } catch (SiteAlreadyInstalledException raceLoser) {
            return InstallToSiteResult.blocked(
                    "A site installation already exists; replacing it is not yet supported.");
        } catch (UncheckedIOException failure) {
            return InstallToSiteResult.blocked(IoFailureMessages.describe("Site installation failed", failure));
        }
        return InstallToSiteResult.installed(identity);
    }

}
