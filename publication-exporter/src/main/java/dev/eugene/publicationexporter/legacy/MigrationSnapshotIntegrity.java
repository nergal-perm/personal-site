package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.reference.PublicFieldsCodec;

import java.util.Objects;

final class MigrationSnapshotIntegrity {

    private MigrationSnapshotIntegrity() {
    }

    static boolean valid(PublicationIdentity identity, CandidateSnapshot snapshot) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(snapshot, "snapshot");
        var references = snapshot.referenceMap();
        return references.schemaVersion() == 1
                && references.identity().equals(identity)
                && references.ruHash().equals(ContentHash.sha256Hex(snapshot.ruBody()))
                && references.enHash().equals(ContentHash.sha256Hex(snapshot.enBody()))
                && references.ruFieldsHash().equals(ContentHash.sha256Hex(PublicFieldsCodec.write(snapshot.ruFields())))
                && references.enFieldsHash().equals(ContentHash.sha256Hex(PublicFieldsCodec.write(snapshot.enFields())))
                && references.structuredDataHash().equals(ContentHash.sha256Hex(snapshot.structuredData()));
    }

    static void requireValid(PublicationIdentity identity, CandidateSnapshot snapshot, String role) {
        if (!valid(identity, snapshot)) {
            throw new IllegalArgumentException(role + " snapshot does not match its journaled reference-map hashes");
        }
    }
}
