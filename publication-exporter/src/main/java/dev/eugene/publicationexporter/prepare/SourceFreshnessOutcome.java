package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

sealed interface SourceFreshnessOutcome
        permits MatchingSource, StaleSource, UnclosedSourceComment, BlockedTransclusionSource, BlockedAssetSource {

    static SourceFreshnessOutcome matches(String sourceHash) {
        return new MatchingSource(sourceHash);
    }

    static SourceFreshnessOutcome stale() {
        return new StaleSource();
    }

    static SourceFreshnessOutcome unclosedComment(int position) {
        return new UnclosedSourceComment(position);
    }

    static SourceFreshnessOutcome blockedTransclusion(String target) {
        return new BlockedTransclusionSource(target);
    }

    static SourceFreshnessOutcome assetBlocked(String reference) {
        return new BlockedAssetSource(reference);
    }

    <T> T resolve(
            Function<String, T> onMatches,
            Supplier<T> onStale,
            Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion,
            Function<String, T> onAssetBlocked);
}

final class MatchingSource implements SourceFreshnessOutcome {

    private final String sourceHash;

    MatchingSource(String sourceHash) {
        this.sourceHash = Objects.requireNonNull(sourceHash, "sourceHash");
    }

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale, Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion, Function<String, T> onAssetBlocked) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        Objects.requireNonNull(onAssetBlocked, "onAssetBlocked");
        return onMatches.apply(sourceHash);
    }
}

final class StaleSource implements SourceFreshnessOutcome {

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale, Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion, Function<String, T> onAssetBlocked) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        Objects.requireNonNull(onAssetBlocked, "onAssetBlocked");
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
            Function<String, T> onMatches, Supplier<T> onStale, Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion, Function<String, T> onAssetBlocked) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        Objects.requireNonNull(onAssetBlocked, "onAssetBlocked");
        return onUnclosedComment.apply(position);
    }
}

final class BlockedTransclusionSource implements SourceFreshnessOutcome {

    private final String target;

    BlockedTransclusionSource(String target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale, Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion, Function<String, T> onAssetBlocked) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        Objects.requireNonNull(onAssetBlocked, "onAssetBlocked");
        return onBlockedTransclusion.apply(target);
    }
}

final class BlockedAssetSource implements SourceFreshnessOutcome {

    private final String reference;

    BlockedAssetSource(String reference) {
        this.reference = Objects.requireNonNull(reference, "reference");
    }

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale, Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion, Function<String, T> onAssetBlocked) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        Objects.requireNonNull(onAssetBlocked, "onAssetBlocked");
        return onAssetBlocked.apply(reference);
    }
}
