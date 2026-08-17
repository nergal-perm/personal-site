package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

public sealed interface LinkResolutionOutcome permits ResolvedLinks, BlockedTransclusion {

    static LinkResolutionOutcome resolved(String body, List<LinkOccurrence> occurrences) {
        return new ResolvedLinks(body, occurrences);
    }

    static LinkResolutionOutcome blockedTransclusion(String target) {
        return new BlockedTransclusion(target);
    }

    <T> T resolve(
            BiFunction<String, List<LinkOccurrence>, T> onResolved,
            Function<String, T> onBlockedTransclusion);
}

final class ResolvedLinks implements LinkResolutionOutcome {

    private final String body;
    private final List<LinkOccurrence> occurrences;

    ResolvedLinks(String body, List<LinkOccurrence> occurrences) {
        this.body = Objects.requireNonNull(body, "body");
        this.occurrences = List.copyOf(Objects.requireNonNull(occurrences, "occurrences"));
    }

    @Override
    public <T> T resolve(
            BiFunction<String, List<LinkOccurrence>, T> onResolved,
            Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onResolved.apply(body, occurrences);
    }
}

final class BlockedTransclusion implements LinkResolutionOutcome {

    private final String target;

    BlockedTransclusion(String target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public <T> T resolve(
            BiFunction<String, List<LinkOccurrence>, T> onResolved,
            Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onBlockedTransclusion.apply(target);
    }
}
