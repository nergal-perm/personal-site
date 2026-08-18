package dev.eugene.publicationexporter.candidate;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import dev.eugene.publicationexporter.hash.ContentHash;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface CandidateWorkspace {

    void install(PublicationIdentity identity, CandidateSnapshot content, List<CandidateAsset> assets);

    default void install(PublicationIdentity identity, String ruBody, String enBody,
            String ruTitle, String enTitle, String ruDescription, String enDescription, ReferenceMap referenceMap) {
        List<PublicField> ruFields = List.of(
                PublicField.of("title", ruTitle), PublicField.of("description", ruDescription));
        List<PublicField> enFields = List.of(
                PublicField.of("title", enTitle), PublicField.of("description", enDescription));
        ReferenceMap snapshotReferenceMap = referenceMap.structuredDataHash().isEmpty()
                ? ReferenceMap.empty(referenceMap.identity(), referenceMap.ruHash(), referenceMap.enHash(),
                        ContentHash.sha256Hex(PublicFieldsCodec.write(ruFields)),
                        ContentHash.sha256Hex(PublicFieldsCodec.write(enFields)), ContentHash.sha256Hex(""))
                : referenceMap;
        install(identity, CandidateSnapshot.of(
                ruBody, enBody, ruFields, enFields, "", snapshotReferenceMap), List.of());
    }

    default void install(PublicationIdentity identity, String ruBody, String enBody,
            List<PublicField> ruFields, List<PublicField> enFields, String structuredData,
            ReferenceMap referenceMap) {
        install(identity, CandidateSnapshot.of(ruBody, enBody, ruFields, enFields, structuredData, referenceMap),
                List.of());
    }

    Optional<CandidatePaths> find(PublicationIdentity identity);

    Optional<CandidateSnapshot> read(PublicationIdentity identity);

    default List<PublicationIdentity> allIdentities() {
        return List.of();
    }

    static CandidateWorkspace create(Path reviewRoot) {
        return new FilesystemCandidateWorkspace(reviewRoot);
    }

    static CandidateWorkspace createNull() {
        return new NullCandidateWorkspace();
    }
}
