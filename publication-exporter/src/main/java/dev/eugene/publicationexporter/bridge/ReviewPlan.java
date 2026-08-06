package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eugene.publicationexporter.candidate.CandidatePaths;

import java.util.List;
import java.util.Objects;

public final class ReviewPlan {

    private static final String BASELINE_ABSENT = "absent";

    private final String baselineState;
    private final List<ReviewTarget> targets;
    private final String ruTitle;
    private final String enTitle;
    private final String ruDescription;
    private final String enDescription;

    private ReviewPlan(
            String baselineState,
            List<ReviewTarget> targets,
            String ruTitle,
            String enTitle,
            String ruDescription,
            String enDescription) {
        this.baselineState = Objects.requireNonNull(baselineState, "baselineState");
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        this.ruTitle = Objects.requireNonNull(ruTitle, "ruTitle");
        this.enTitle = Objects.requireNonNull(enTitle, "enTitle");
        this.ruDescription = Objects.requireNonNull(ruDescription, "ruDescription");
        this.enDescription = Objects.requireNonNull(enDescription, "enDescription");
    }

    public static ReviewPlan firstPublication(
            CandidatePaths candidatePaths,
            String ruTitle,
            String enTitle,
            String ruDescription,
            String enDescription) {
        Objects.requireNonNull(candidatePaths, "candidatePaths");
        return new ReviewPlan(BASELINE_ABSENT, List.of(
                ReviewTarget.of("ru", candidatePaths.ruPath().toString(), null),
                ReviewTarget.of("en", candidatePaths.enPath().toString(), null)),
                ruTitle, enTitle, ruDescription, enDescription);
    }

    @JsonProperty("baselineState")
    public String baselineState() {
        return baselineState;
    }

    @JsonProperty("targets")
    public List<ReviewTarget> targets() {
        return targets;
    }

    @JsonProperty("ruTitle")
    public String ruTitle() {
        return ruTitle;
    }

    @JsonProperty("enTitle")
    public String enTitle() {
        return enTitle;
    }

    @JsonProperty("ruDescription")
    public String ruDescription() {
        return ruDescription;
    }

    @JsonProperty("enDescription")
    public String enDescription() {
        return enDescription;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReviewPlan that)) {
            return false;
        }
        return baselineState.equals(that.baselineState)
                && targets.equals(that.targets)
                && ruTitle.equals(that.ruTitle)
                && enTitle.equals(that.enTitle)
                && ruDescription.equals(that.ruDescription)
                && enDescription.equals(that.enDescription);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baselineState, targets, ruTitle, enTitle, ruDescription, enDescription);
    }

    @Override
    public String toString() {
        return "ReviewPlan[baselineState=" + baselineState
                + ", targets=" + targets
                + ", ruTitle=" + ruTitle
                + ", enTitle=" + enTitle
                + ", ruDescription=" + ruDescription
                + ", enDescription=" + enDescription + "]";
    }
}
