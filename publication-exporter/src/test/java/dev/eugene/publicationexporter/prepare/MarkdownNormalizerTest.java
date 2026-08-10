package dev.eugene.publicationexporter.prepare;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class MarkdownNormalizerTest {

    @Test
    void stripsCommentWhenLaterBacktickRunIsLongerThanUnclosedInlineCode() {
        MarkdownNormalizationOutcome outcome = MarkdownNormalizer.normalize(
                "before `` %% private %% ``` after");

        assertEquals("before ``  ``` after", outcome.resolve(
                normalized -> normalized,
                position -> fail("The comment should be closed at position " + position)));
    }
}
