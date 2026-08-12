package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.List;

public final class LegacyCandidateSnapshotFixture {

    private LegacyCandidateSnapshotFixture() {
    }

    public static CandidateSnapshot of(String ruBody, String enBody, String ruTitle, String enTitle,
            String ruDescription, String enDescription, ReferenceMap referenceMap) {
        return CandidateSnapshot.of(ruBody, enBody,
                List.of(PublicField.of("title", ruTitle), PublicField.of("description", ruDescription)),
                List.of(PublicField.of("title", enTitle), PublicField.of("description", enDescription)),
                "", referenceMap);
    }
}
