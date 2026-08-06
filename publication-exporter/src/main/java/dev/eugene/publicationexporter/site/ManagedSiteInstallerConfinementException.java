package dev.eugene.publicationexporter.site;

import java.nio.file.Path;

public final class ManagedSiteInstallerConfinementException extends IllegalStateException {

    public ManagedSiteInstallerConfinementException(Path candidate, Path resolvedCandidate, Path siteRoot) {
        super("Path escapes the site root " + siteRoot + ": " + candidate
                + " (resolved: " + resolvedCandidate + ")");
    }
}
