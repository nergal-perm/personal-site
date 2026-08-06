package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

public interface ReleaseOutputStore {

    void install(PublicationIdentity identity, String ruBody, String enBody, ReleaseProvenance provenance);

    static ReleaseOutputStore createNull() {
        return new NullReleaseOutputStore();
    }
}
