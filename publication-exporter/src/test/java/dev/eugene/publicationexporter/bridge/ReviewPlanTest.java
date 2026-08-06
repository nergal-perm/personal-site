package dev.eugene.publicationexporter.bridge;

import dev.eugene.publicationexporter.candidate.CandidatePaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewPlanTest {

    @Test
    void firstPublicationReportsAbsentBaselineAndOrderedRuThenEnTargets() {
        CandidatePaths paths = CandidatePaths.of(
                Path.of("/review/blog/my-essay/candidate/ru.md"),
                Path.of("/review/blog/my-essay/candidate/en.md"));

        ReviewPlan plan = ReviewPlan.firstPublication(
                paths, "RU title", "EN title", "RU description.", "EN description.");

        assertEquals("absent", plan.baselineState());
        assertEquals(List.of(
                ReviewTarget.of("ru", "/review/blog/my-essay/candidate/ru.md", null),
                ReviewTarget.of("en", "/review/blog/my-essay/candidate/en.md", null)),
                plan.targets());
        assertEquals("RU title", plan.ruTitle());
        assertEquals("EN title", plan.enTitle());
        assertEquals("RU description.", plan.ruDescription());
        assertEquals("EN description.", plan.enDescription());
    }

    @Test
    void targetsListIsImmutable() {
        ReviewPlan plan = ReviewPlan.firstPublication(
                CandidatePaths.of(Path.of("ru.md"), Path.of("en.md")),
                "RU title", "EN title", "RU description.", "EN description.");

        assertThrows(UnsupportedOperationException.class,
                () -> plan.targets().add(ReviewTarget.of("ru", "x", null)));
    }

    @Test
    void candidatePathsIsRejectedAtConstruction() {
        assertThrows(NullPointerException.class,
                () -> ReviewPlan.firstPublication(
                        null, "RU title", "EN title", "RU description.", "EN description."));
    }

    @Test
    void metadataIsRejectedAtConstruction() {
        CandidatePaths paths = CandidatePaths.of(Path.of("ru.md"), Path.of("en.md"));

        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReviewPlan.firstPublication(
                        paths, "RU title", "EN title", "RU description.", null));

        assertEquals("enDescription", exception.getMessage());
    }
}
