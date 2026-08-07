package dev.eugene.publicationexporter.installtosite;

import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceConfinementException;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspaceStateException;
import dev.eugene.publicationexporter.bridge.IoFailureMessages;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.site.ManagedSiteInstallationFailedAfterRecoveryException;
import dev.eugene.publicationexporter.site.ManagedSiteInstallOutcome;
import dev.eugene.publicationexporter.site.ManagedSiteInstaller;
import dev.eugene.publicationexporter.site.ManagedSiteRecoveryException;
import dev.eugene.publicationexporter.site.SiteAlreadyInstalledException;
import dev.eugene.publicationexporter.site.UnsafeManagedSiteEntryException;

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
        } catch (ApprovedSnapshotWorkspaceStateException failure) {
            return InstallToSiteResult.blocked("Approved snapshot lookup failed: " + failure.getMessage());
        }
        if (approved.isEmpty()) {
            return InstallToSiteResult.blocked("No approved snapshot exists to install.");
        }
        return installApprovedSnapshot(identity, approved.get());
    }

    private InstallToSiteResult installApprovedSnapshot(PublicationIdentity identity, CandidateSnapshot planned) {
        try {
            return approvedSnapshotWorkspace.withApprovalLock(
                    identity, () -> installApprovedSnapshotUnderLock(identity, planned));
        } catch (UncheckedIOException failure) {
            return InstallToSiteResult.blocked(IoFailureMessages.describe("Approved snapshot lookup failed", failure));
        } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
            return InstallToSiteResult.blocked("Approved snapshot lookup failed: " + failure.getMessage());
        } catch (ApprovedSnapshotWorkspaceStateException failure) {
            return InstallToSiteResult.blocked("Approved snapshot lookup failed: " + failure.getMessage());
        }
    }

    private InstallToSiteResult installApprovedSnapshotUnderLock(
            PublicationIdentity identity, CandidateSnapshot planned) {
        Optional<CandidateSnapshot> current;
        try {
            current = readCurrentApprovedSnapshot(identity);
        } catch (LookupFailure failure) {
            return InstallToSiteResult.blocked(failure.getMessage());
        }
        if (current.isEmpty() || !planned.referenceMap().sameContentAs(current.get().referenceMap())) {
            return InstallToSiteResult.blocked(
                    "Approved snapshot changed since release was planned; site installation was not attempted.");
        }
        ManagedSiteInstallOutcome outcome;
        try {
            outcome = managedSiteInstaller.installWithOutcome(identity, planned);
        } catch (SiteAlreadyInstalledException raceLoser) {
            return InstallToSiteResult.blocked(
                    "Another site installation is already in progress for this publication.");
        } catch (ManagedSiteInstallationFailedAfterRecoveryException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof UncheckedIOException ioFailure) {
                return InstallToSiteResult.blocked(IoFailureMessages.describe(
                        "Managed site recovery restored a coherent prior generation, but subsequent site installation failed",
                        ioFailure));
            }
            String detail = cause.getMessage();
            return InstallToSiteResult.blocked(detail == null || detail.isBlank()
                    ? "Managed site recovery restored a coherent prior generation, but subsequent site installation failed."
                    : "Managed site recovery restored a coherent prior generation, but subsequent site installation failed: "
                            + detail);
        } catch (UncheckedIOException failure) {
            return InstallToSiteResult.blocked(IoFailureMessages.describe("Site installation failed", failure));
        } catch (ManagedSiteRecoveryException failure) {
            return InstallToSiteResult.blocked("Site installation blocked during managed-site recovery: "
                    + failure.getMessage());
        } catch (UnsafeManagedSiteEntryException failure) {
            return InstallToSiteResult.blocked(
                    "Site installation refused unsafe managed content: " + failure.getMessage());
        }
        return outcome.recoveredBeforeInstall()
                ? InstallToSiteResult.installedAfterRecovery(identity)
                : InstallToSiteResult.installed(identity);
    }

    private Optional<CandidateSnapshot> readCurrentApprovedSnapshot(PublicationIdentity identity) {
        try {
            return approvedSnapshotWorkspace.read(identity);
        } catch (UncheckedIOException failure) {
            throw new LookupFailure(IoFailureMessages.describe("Approved snapshot lookup failed", failure));
        } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
            throw new LookupFailure("Approved snapshot lookup failed: " + failure.getMessage());
        } catch (ApprovedSnapshotWorkspaceStateException failure) {
            throw new LookupFailure("Approved snapshot lookup failed: " + failure.getMessage());
        }
    }

    private static final class LookupFailure extends RuntimeException {
        LookupFailure(String message) {
            super(message);
        }
    }

}
