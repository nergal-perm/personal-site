package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.function.Function;

public sealed interface MarkdownNormalizationOutcome permits NormalizedMarkdown, UnclosedObsidianComment {

    static MarkdownNormalizationOutcome normalized(String body) {
        return new NormalizedMarkdown(body);
    }

    static MarkdownNormalizationOutcome unclosedComment(int position) {
        return new UnclosedObsidianComment(position);
    }

    <T> T resolve(
            Function<String, T> onNormalized,
            Function<Integer, T> onUnclosedComment);
}

final class NormalizedMarkdown implements MarkdownNormalizationOutcome {

    private final String body;

    NormalizedMarkdown(String body) {
        this.body = Objects.requireNonNull(body, "body");
    }

    @Override
    public <T> T resolve(Function<String, T> onNormalized, Function<Integer, T> onUnclosedComment) {
        Objects.requireNonNull(onNormalized, "onNormalized");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        return onNormalized.apply(body);
    }
}

final class UnclosedObsidianComment implements MarkdownNormalizationOutcome {

    private final int position;

    UnclosedObsidianComment(int position) {
        this.position = position;
    }

    @Override
    public <T> T resolve(Function<String, T> onNormalized, Function<Integer, T> onUnclosedComment) {
        Objects.requireNonNull(onNormalized, "onNormalized");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        return onUnclosedComment.apply(position);
    }
}
