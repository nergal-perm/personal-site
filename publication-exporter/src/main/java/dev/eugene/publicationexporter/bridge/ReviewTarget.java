package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public final class ReviewTarget {

    private final String language;
    private final String proposedPath;
    private final String publishedPath;

    private ReviewTarget(String language, String proposedPath, String publishedPath) {
        this.language = Objects.requireNonNull(language, "language");
        this.proposedPath = Objects.requireNonNull(proposedPath, "proposedPath");
        this.publishedPath = publishedPath;
    }

    public static ReviewTarget of(String language, String proposedPath, String publishedPath) {
        return new ReviewTarget(language, proposedPath, publishedPath);
    }

    @JsonProperty("language")
    public String language() {
        return language;
    }

    @JsonProperty("proposedPath")
    public String proposedPath() {
        return proposedPath;
    }

    @JsonProperty("publishedPath")
    public String publishedPath() {
        return publishedPath;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReviewTarget that)) {
            return false;
        }
        return language.equals(that.language)
                && proposedPath.equals(that.proposedPath)
                && Objects.equals(publishedPath, that.publishedPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(language, proposedPath, publishedPath);
    }

    @Override
    public String toString() {
        return "ReviewTarget[language=" + language + ", proposedPath=" + proposedPath
                + ", publishedPath=" + publishedPath + "]";
    }
}
