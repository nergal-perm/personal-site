package dev.eugene.publicationexporter.approved;

import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;

import java.util.List;

final class LegacyApprovedSnapshotInstaller {

    private LegacyApprovedSnapshotInstaller() {
    }

    static CandidateSnapshot snapshot(String ruBody, String enBody, String ruTitle, String enTitle,
            String ruDescription, String enDescription, ReferenceMap referenceMap) {
        List<PublicField> ruFields = List.of(
                PublicField.of("title", ruTitle), PublicField.of("description", ruDescription));
        List<PublicField> enFields = List.of(
                PublicField.of("title", enTitle), PublicField.of("description", enDescription));
        ReferenceMap canonical = ReferenceMap.empty(referenceMap.identity(), referenceMap.ruHash(), referenceMap.enHash(),
                ContentHash.sha256Hex(PublicFieldsCodec.write(ruFields)),
                ContentHash.sha256Hex(PublicFieldsCodec.write(enFields)), ContentHash.sha256Hex(""));
        return CandidateSnapshot.of(ruBody, enBody, ruFields, enFields, "", canonical);
    }
}
