package dev.eugene.publicationexporter.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewTargetTest {

    @Test
    void accessorsReturnConstructedValues() {
        ReviewTarget target = ReviewTarget.of("ru", "/review/blog/my-essay/candidate/ru.md", null);

        assertEquals("ru", target.language());
        assertEquals("/review/blog/my-essay/candidate/ru.md", target.proposedPath());
        assertNull(target.publishedPath());
    }

    @Test
    void equalTargetsBuiltSeparatelyAreEqual() {
        assertEquals(
                ReviewTarget.of("ru", "/ru.md", null),
                ReviewTarget.of("ru", "/ru.md", null));
    }

    @Test
    void languageIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReviewTarget.of(null, "/ru.md", null));
        assertEquals("language", exception.getMessage());
    }

    @Test
    void proposedPathIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> ReviewTarget.of("ru", null, null));
        assertEquals("proposedPath", exception.getMessage());
    }
}
