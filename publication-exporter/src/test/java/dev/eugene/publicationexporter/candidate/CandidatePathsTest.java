package dev.eugene.publicationexporter.candidate;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidatePathsTest {

    @Test
    void accessorsReturnConstructedValues() {
        CandidatePaths paths = CandidatePaths.of(
                Path.of("/review/blog/my-essay/candidate/ru.md"),
                Path.of("/review/blog/my-essay/candidate/en.md"));

        assertEquals(Path.of("/review/blog/my-essay/candidate/ru.md"), paths.ruPath());
        assertEquals(Path.of("/review/blog/my-essay/candidate/en.md"), paths.enPath());
    }

    @Test
    void equalPathsBuiltSeparatelyAreEqual() {
        assertEquals(
                CandidatePaths.of(Path.of("ru.md"), Path.of("en.md")),
                CandidatePaths.of(Path.of("ru.md"), Path.of("en.md")));
    }

    @Test
    void ruPathIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> CandidatePaths.of(null, Path.of("en.md")));
        assertEquals("ruPath", exception.getMessage());
    }

    @Test
    void enPathIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> CandidatePaths.of(Path.of("ru.md"), null));
        assertEquals("enPath", exception.getMessage());
    }
}
