package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class NullManagedSiteInstaller implements ManagedSiteInstaller {

    private final Map<PublicationIdentity, CandidateSnapshot> installed = new HashMap<>();

    @Override
    public void install(PublicationIdentity identity, CandidateSnapshot approvedSnapshot) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(approvedSnapshot, "approvedSnapshot");
        installed.put(identity, approvedSnapshot);
    }

    public Map<PublicationIdentity, CandidateSnapshot> installed() {
        return Map.copyOf(installed);
    }
}
