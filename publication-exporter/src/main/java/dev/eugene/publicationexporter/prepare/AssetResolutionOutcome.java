package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.candidate.CandidateAsset;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

public sealed interface AssetResolutionOutcome permits ResolvedAssets, BlockedAsset {

    static AssetResolutionOutcome resolved(String body, List<CandidateAsset> assets) {
        return new ResolvedAssets(body, assets);
    }

    static AssetResolutionOutcome blocked(String reference) {
        return new BlockedAsset(reference);
    }

    <T> T resolve(
            BiFunction<String, List<CandidateAsset>, T> onResolved,
            Function<String, T> onBlocked);
}

final class ResolvedAssets implements AssetResolutionOutcome {

    private final String body;
    private final List<CandidateAsset> assets;

    ResolvedAssets(String body, List<CandidateAsset> assets) {
        this.body = Objects.requireNonNull(body, "body");
        this.assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
    }

    @Override
    public <T> T resolve(BiFunction<String, List<CandidateAsset>, T> onResolved, Function<String, T> onBlocked) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlocked, "onBlocked");
        return onResolved.apply(body, assets);
    }
}

final class BlockedAsset implements AssetResolutionOutcome {

    private final String reference;

    BlockedAsset(String reference) {
        this.reference = Objects.requireNonNull(reference, "reference");
    }

    @Override
    public <T> T resolve(BiFunction<String, List<CandidateAsset>, T> onResolved, Function<String, T> onBlocked) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlocked, "onBlocked");
        return onBlocked.apply(reference);
    }
}
