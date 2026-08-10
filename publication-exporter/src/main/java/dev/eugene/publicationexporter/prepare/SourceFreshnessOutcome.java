package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

sealed interface SourceFreshnessOutcome permits MatchingSource, StaleSource, UnclosedSourceComment {

    static SourceFreshnessOutcome matches(String sourceHash) {
        return new MatchingSource(sourceHash);
    }

    static SourceFreshnessOutcome stale() {
        return new StaleSource();
    }

    static SourceFreshnessOutcome unclosedComment(int position) {
        return new UnclosedSourceComment(position);
    }

    <T> T resolve(
            Function<String, T> onMatches,
            Supplier<T> onStale,
            Function<Integer, T> onUnclosedComment);
}

final class MatchingSource implements SourceFreshnessOutcome {

    private final String sourceHash;

    MatchingSource(String sourceHash) {
        this.sourceHash = Objects.requireNonNull(sourceHash, "sourceHash");
    }

    @Override
    public <T> T resolve(
            Function<String, T> onMatches,
            Supplier<T> onStale,
            Function<Integer, T> onUnclosedComment) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        return onMatches.apply(sourceHash);
    }
}

final class StaleSource implements SourceFreshnessOutcome {

    @Override
    public <T> T resolve(
            Function<String, T> onMatches,
            Supplier<T> onStale,
            Function<Integer, T> onUnclosedComment) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        return onStale.get();
    }
}

final class UnclosedSourceComment implements SourceFreshnessOutcome {

    private final int position;

    UnclosedSourceComment(int position) {
        this.position = position;
    }

    @Override
    public <T> T resolve(
            Function<String, T> onMatches,
            Supplier<T> onStale,
            Function<Integer, T> onUnclosedComment) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        return onUnclosedComment.apply(position);
    }
}
