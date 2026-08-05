package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.candidate.CandidatePaths;

import java.util.List;
import java.util.Objects;

public final class ReviewPlan {

    private static final String BASELINE_ABSENT = "absent";

    private final String baselineState;
    private final List<ReviewTarget> targets;

    private ReviewPlan(String baselineState, List<ReviewTarget> targets) {
        this.baselineState = Objects.requireNonNull(baselineState, "baselineState");
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
    }

    public static ReviewPlan firstPublication(CandidatePaths candidatePaths) {
        Objects.requireNonNull(candidatePaths, "candidatePaths");
        return new ReviewPlan(BASELINE_ABSENT, List.of(
                ReviewTarget.of("ru", candidatePaths.ruPath().toString(), null),
                ReviewTarget.of("en", candidatePaths.enPath().toString(), null)));
    }

    @JsonProperty("baselineState")
    public String baselineState() {
        return baselineState;
    }

    @JsonProperty("targets")
    public List<ReviewTarget> targets() {
        return targets;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReviewPlan that)) {
            return false;
        }
        return baselineState.equals(that.baselineState) && targets.equals(that.targets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baselineState, targets);
    }

    @Override
    public String toString() {
        return "ReviewPlan[baselineState=" + baselineState + ", targets=" + targets + "]";
    }
}
