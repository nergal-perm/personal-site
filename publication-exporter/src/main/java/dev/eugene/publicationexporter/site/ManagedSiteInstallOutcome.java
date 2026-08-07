package dev.eugene.publicationexporter.site;

public enum ManagedSiteInstallOutcome {
    INSTALLED,
    RECOVERED_AND_INSTALLED;

    public boolean recoveredBeforeInstall() {
        return this == RECOVERED_AND_INSTALLED;
    }
}
