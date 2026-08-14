package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

public sealed interface LinkResolutionOutcome permits ResolvedLinks, BlockedTransclusion {

    static LinkResolutionOutcome resolved(String body, Set<String> privateTargetStems) {
        return new ResolvedLinks(body, privateTargetStems);
    }

    static LinkResolutionOutcome blockedTransclusion(String target) {
        return new BlockedTransclusion(target);
    }

    <T> T resolve(
            BiFunction<String, Set<String>, T> onResolved,
            Function<String, T> onBlockedTransclusion);
}

final class ResolvedLinks implements LinkResolutionOutcome {

    private final String body;
    private final Set<String> privateTargetStems;

    ResolvedLinks(String body, Set<String> privateTargetStems) {
        this.body = Objects.requireNonNull(body, "body");
        this.privateTargetStems = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(privateTargetStems, "privateTargetStems")));
    }

    @Override
    public <T> T resolve(
            BiFunction<String, Set<String>, T> onResolved, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onResolved.apply(body, privateTargetStems);
    }
}

final class BlockedTransclusion implements LinkResolutionOutcome {

    private final String target;

    BlockedTransclusion(String target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public <T> T resolve(
            BiFunction<String, Set<String>, T> onResolved, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onBlockedTransclusion.apply(target);
    }
}
