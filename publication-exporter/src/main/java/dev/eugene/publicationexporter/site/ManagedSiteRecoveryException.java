package dev.eugene.publicationexporter.site;

import java.nio.file.Path;

public final class ManagedSiteRecoveryException extends IllegalStateException {

    private ManagedSiteRecoveryException(String message) {
        super(message);
    }

    static ManagedSiteRecoveryException provenanceMismatchWithoutBackups(Path provenance) {
        return new ManagedSiteRecoveryException(
                "Managed site recovery cannot continue because provenance does not match the current managed tree"
                        + " and no locale backups exist: " + provenance);
    }
}
