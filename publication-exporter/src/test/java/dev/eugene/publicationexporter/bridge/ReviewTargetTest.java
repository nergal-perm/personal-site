package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void serializesNullPublishedPathAsExplicitJsonNull() throws Exception {
        ReviewTarget target = ReviewTarget.of("ru", "/ru.md", null);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(target));

        assertEquals("ru", parsed.get("language").asText());
        assertEquals("/ru.md", parsed.get("proposedPath").asText());
        assertTrue(parsed.has("publishedPath"));
        assertTrue(parsed.get("publishedPath").isNull());
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
