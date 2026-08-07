package dev.eugene.publicationexporter.site;

import java.util.Objects;

public final class ManagedSiteInstallationFailedAfterRecoveryException extends IllegalStateException {

    private ManagedSiteInstallationFailedAfterRecoveryException(Throwable cause) {
        super("Managed site recovery completed, but subsequent installation failed", cause);
    }

    static ManagedSiteInstallationFailedAfterRecoveryException afterRecovery(Throwable cause) {
        return new ManagedSiteInstallationFailedAfterRecoveryException(Objects.requireNonNull(cause, "cause"));
    }
}
