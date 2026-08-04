package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.ArrayList;
import java.util.List;

public final class NullCandidateWorkspace implements CandidateWorkspace {

    private final List<InstalledCandidate> installed = new ArrayList<>();

    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        installed.add(new InstalledCandidate(identity, ruBody, enBody, referenceMap));
    }

    public List<InstalledCandidate> installed() {
        return List.copyOf(installed);
    }

    public record InstalledCandidate(
            PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
    }
}
