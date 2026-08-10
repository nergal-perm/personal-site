package dev.eugene.publicationexporter.prepare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class MarkdownNormalizerTest {

    private static String normalizedBodyOrFail(String body) {
        return MarkdownNormalizer.normalize(body).resolve(
                normalized -> normalized,
                position -> fail("Expected a normalized result but got an unclosed comment at " + position));
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void stripsCommentWhenLaterBacktickRunIsLongerThanUnclosedInlineCode() {
        String body = "before `` %% private %% ``` after";

        assertEquals("before ``  ``` after", normalizedBodyOrFail(body));
    }

    @Test
    void tooShortClosingFenceIsSkippedInFavorOfTheRealCloser() {
        String body = "Visible.\n\n````\nStill hidden.\n```\n%% not stripped if fence closes correctly %%\n````\n\nAfter.";

        assertEquals(body, normalizedBodyOrFail(body));
    }

    @Test
    void shorterBacktickRunInsideDoesNotFalselyCloseInlineCode() {
        String body = "``code with a lone ` %% not stripped if span closes correctly %% backtick inside``  after.";

        assertEquals(body, normalizedBodyOrFail(body));
    }

    @Test
    void fencedCodeIsRecognisedWithCarriageReturnLineEndings() {
        String body = "Visible.\r\n\r\n~~~\r\n%% not a comment, this is code %%\r\n~~~\r\n\r\nAfter.";

        assertEquals(body, normalizedBodyOrFail(body));
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void unclosedFenceProtectsThroughEndOfBodyWithoutBlocking() {
        String body = "Visible.\n\n```\n%% not a comment, this is unclosed code %%";

        assertEquals(body, normalizedBodyOrFail(body));
    }

    @Test
    void unclosedObsidianCommentReturnsItsStartPosition() {
        String body = "Visible. %% never closed";

        int position = MarkdownNormalizer.normalize(body).resolve(
                normalized -> fail("Expected an unclosed-comment outcome but normalization succeeded: " + normalized),
                reportedPosition -> reportedPosition);

        assertEquals(body.indexOf("%%"), position);
    }
}
