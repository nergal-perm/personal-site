package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.function.Function;

public sealed interface LinkResolutionOutcome permits ResolvedLinks, BlockedTransclusion {

    static LinkResolutionOutcome resolved(String body) {
        return new ResolvedLinks(body);
    }

    static LinkResolutionOutcome blockedTransclusion(String target) {
        return new BlockedTransclusion(target);
    }

    <T> T resolve(
            Function<String, T> onResolved,
            Function<String, T> onBlockedTransclusion);
}

final class ResolvedLinks implements LinkResolutionOutcome {

    private final String body;

    ResolvedLinks(String body) {
        this.body = Objects.requireNonNull(body, "body");
    }

    @Override
    public <T> T resolve(Function<String, T> onResolved, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onResolved.apply(body);
    }
}

final class BlockedTransclusion implements LinkResolutionOutcome {

    private final String target;

    BlockedTransclusion(String target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public <T> T resolve(Function<String, T> onResolved, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onBlockedTransclusion.apply(target);
    }
}
