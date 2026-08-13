package dev.eugene.publicationexporter.admission;

import java.util.Objects;

public final class ManagedArtifact {

    private final String relativePath;
    private final String content;
    private final String collisionMarkerLine;

    private ManagedArtifact(String relativePath, String content, String collisionMarkerLine) {
        this.relativePath = Objects.requireNonNull(relativePath, "relativePath");
        this.content = Objects.requireNonNull(content, "content");
        this.collisionMarkerLine = Objects.requireNonNull(collisionMarkerLine, "collisionMarkerLine");
    }

    public static ManagedArtifact of(String relativePath, String content, String collisionMarkerLine) {
        return new ManagedArtifact(relativePath, content, collisionMarkerLine);
    }

    public String relativePath() {
        return relativePath;
    }

    public String content() {
        return content;
    }

    /**
     * The exact line this artifact's own kind marker appears as, scanned for verbatim in an
     * existing file at the same path before replacement, to detect two different kinds
     * colliding on one (collection, publicId) address. See FilesystemManagedSiteInstaller's
     * existing requireNoKindCollision.
     */
    public String collisionMarkerLine() {
        return collisionMarkerLine;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ManagedArtifact that)) {
            return false;
        }
        return relativePath.equals(that.relativePath) && content.equals(that.content)
                && collisionMarkerLine.equals(that.collisionMarkerLine);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relativePath, content, collisionMarkerLine);
    }
}
