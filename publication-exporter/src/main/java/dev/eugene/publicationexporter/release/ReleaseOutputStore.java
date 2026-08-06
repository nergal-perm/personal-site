package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import java.nio.file.Path;

public interface ReleaseOutputStore {

    void install(PublicationIdentity identity, String ruBody, String enBody, ReleaseProvenance provenance);

    static ReleaseOutputStore create(Path outputRoot) {
        return new FilesystemReleaseOutputStore(outputRoot);
    }

    static ReleaseOutputStore createNull() {
        return new NullReleaseOutputStore();
    }
}
