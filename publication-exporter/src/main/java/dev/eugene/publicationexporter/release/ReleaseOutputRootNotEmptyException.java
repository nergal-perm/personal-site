package dev.eugene.publicationexporter.release;

import java.nio.file.Path;

public final class ReleaseOutputRootNotEmptyException extends IllegalStateException {

    public ReleaseOutputRootNotEmptyException(Path root) {
        super("Output root already contains review-workspace content (approved/candidate) "
                + "and cannot double as a release output root: " + root);
    }
}
