package dev.eugene.publicationexporter.candidate;

import java.util.Arrays;
import java.util.Objects;

public final class CandidateAsset {

    private final String publicName;
    private final byte[] content;

    private CandidateAsset(String publicName, byte[] content) {
        this.publicName = Objects.requireNonNull(publicName, "publicName");
        this.content = Objects.requireNonNull(content, "content").clone();
    }

    public static CandidateAsset of(String publicName, byte[] content) {
        return new CandidateAsset(publicName, content);
    }

    public String publicName() {
        return publicName;
    }

    public byte[] content() {
        return content.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CandidateAsset that)) {
            return false;
        }
        return publicName.equals(that.publicName) && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(publicName, Arrays.hashCode(content));
    }

    @Override
    public String toString() {
        return "CandidateAsset[publicName=" + publicName + ", content=" + content.length + " bytes]";
    }
}
