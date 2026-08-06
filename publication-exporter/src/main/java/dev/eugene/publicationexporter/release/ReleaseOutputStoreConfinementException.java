package dev.eugene.publicationexporter.release;

import java.nio.file.Path;

public final class ReleaseOutputStoreConfinementException extends IllegalStateException {

    public ReleaseOutputStoreConfinementException(Path candidate, Path resolvedCandidate, Path outputRoot) {
        super("Release directory escapes output root: " + candidate
                + " resolved to " + resolvedCandidate + " outside " + outputRoot);
    }
}
