package dev.eugene.publicationexporter.note;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontmatterTest {

    @Test
    void withScalarSetReplacesAnExistingKeyInPlace() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                workflowStatus: ready_for_review
                publicId: my-essay
                ---
                # Body

                Text.""");

        String updated = frontmatter.withScalarSet("workflowStatus", "ready_to_publish");

        assertEquals("""
                ---
                publish: true
                workflowStatus: ready_to_publish
                publicId: my-essay
                ---
                # Body

                Text.""", updated);
    }

    @Test
    void withScalarSetReplacesOnlyTheExistingValueBytes() {
        String source = "---\nworkflowStatus:    stale   # keep\npublicId: my-essay\n---\nBody.";
        Frontmatter frontmatter = Frontmatter.parse(source);

        String updated = frontmatter.withScalarSet("workflowStatus", "ready_for_review");

        assertEquals(
                "---\nworkflowStatus:    ready_for_review   # keep\npublicId: my-essay\n---\nBody.",
                updated);
    }

    @Test
    void withScalarSetInsertsAnAbsentKeyBeforeTheClosingDelimiter() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicId: my-essay
                ---
                Body.""");

        String updated = frontmatter.withScalarSet("workflowStatus", "not_prepared");

        assertEquals("""
                ---
                publish: true
                publicId: my-essay
                workflowStatus: not_prepared
                ---
                Body.""", updated);
    }

    @Test
    void withScalarSetPreservesLineEndingsAndBodyExactly() {
        String source = "---\r\npublish: true\r\n---\r\nBody with trailing space \r\n";
        Frontmatter frontmatter = Frontmatter.parse(source);

        String updated = frontmatter.withScalarSet("workflowStatus", "stale");

        assertTrue(updated.startsWith("---\r\npublish: true\r\nworkflowStatus: stale\r\n---\r\n"));
        assertTrue(updated.endsWith("Body with trailing space \r\n"));
    }

    @Test
    void withScalarSetThrowsOnNoFrontmatterWhenBodyContainsDelimiter() {
        Frontmatter frontmatter = Frontmatter.parse("Text\n---\nMore");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> frontmatter.withScalarSet("workflowStatus", "ready"));

        assertEquals("withScalarSet requires a note with frontmatter already present.", exception.getMessage());
    }

    @Test
    void withScalarSetPreservesLoneCrLineEndingsAndBodyExactly() {
        String source = "---\rpublicId: my-essay\r---\r# My Essay\rPlain prose.\r";
        Frontmatter frontmatter = Frontmatter.parse(source);

        String updated = frontmatter.withScalarSet("workflowStatus", "stale");

        assertEquals("---\rpublicId: my-essay\rworkflowStatus: stale\r---\r# My Essay\rPlain prose.\r",
                updated);
    }

    @Test
    void withScalarSetPreservesMixedLineEndingsOutsideTheTouchedLine() {
        String source = "---\r\npublish: true\n---\r\nBody.\n";
        Frontmatter frontmatter = Frontmatter.parse(source);

        String updated = frontmatter.withScalarSet("workflowStatus", "stale");

        assertEquals("---\r\npublish: true\nworkflowStatus: stale\n---\r\nBody.\n", updated);
    }

    @Test
    void parsesStringValue() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                ---
                # Body""");

        assertEquals(Optional.of("my-essay"), frontmatter.string("publicId"));
    }

    @Test
    void parsesBooleanTrueFlag() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                ---
                # Body""");

        assertTrue(frontmatter.flag("publish"));
    }

    @Test
    void missingKeyReturnsEmptyOptional() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                ---
                """);

        assertEquals(Optional.empty(), frontmatter.string("sourceId"));
    }

    @Test
    void missingFlagReturnsFalse() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                ---
                """);

        assertFalse(frontmatter.flag("publish"));
    }

    @Test
    void noOpeningDelimiterYieldsAllValuesAbsent() {
        Frontmatter frontmatter = Frontmatter.parse("# Just a body, no frontmatter block");

        assertEquals(Optional.empty(), frontmatter.string("publicId"));
        assertFalse(frontmatter.flag("publish"));
    }

    @Test
    void quotedValueIsUnquoted() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: "my-essay"
                ---
                """);

        assertEquals(Optional.of("my-essay"), frontmatter.string("publicId"));
    }

    @Test
    void quotedTrueIsNotABooleanFlag() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: "true"
                ---
                """);

        assertFalse(frontmatter.flag("publish"));
    }

    @Test
    void bareNullIsNotAString() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                sourceId: null
                ---
                """);

        assertEquals(Optional.empty(), frontmatter.string("sourceId"));
    }

    @Test
    void bareBooleanIsNotAString() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                sourceId: true
                ---
                """);

        assertEquals(Optional.empty(), frontmatter.string("sourceId"));
    }

    @Test
    void quotedNullIsAString() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                sourceId: "null"
                ---
                """);

        assertEquals(Optional.of("null"), frontmatter.string("sourceId"));
    }

    @Test
    void duplicateKeyMakesTheWholeBlockUnparseable() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: first
                sourceId: source
                publicId: second
                ---
                """);

        assertEquals(Optional.empty(), frontmatter.string("publicId"));
        assertEquals(Optional.empty(), frontmatter.string("sourceId"));
    }

    @Test
    void contentAfterClosingDelimiterIsNotParsedAsFrontmatter() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                ---
                sourceId: this-looks-like-frontmatter-but-is-body-text""");

        assertEquals(Optional.empty(), frontmatter.string("sourceId"));
    }

    @Test
    void nullSourceIsRejectedAtParseTime() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> Frontmatter.parse(null));
        assertEquals("noteSource", exception.getMessage());
    }

    @Test
    void unterminatedFrontmatterBlockDoesNotParseBodyAsMetadata() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                # Body starts here
                Note: see also the appendix""");

        assertEquals(Optional.empty(), frontmatter.string("publicId"));
        assertEquals(Optional.empty(), frontmatter.string("Note"));
    }

    @Test
    void bodyReturnsTextAfterTheClosingDelimiter() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                ---
                # My Essay

                Plain prose body.""");

        assertEquals("# My Essay\n\nPlain prose body.", frontmatter.body());
    }

    @Test
    void bodyIsTheWholeSourceWhenNoFrontmatterBlockExists() {
        Frontmatter frontmatter = Frontmatter.parse("# Just a body, no frontmatter block");

        assertEquals("# Just a body, no frontmatter block", frontmatter.body());
    }

    @Test
    void bodyIsTheWholeSourceWhenAFrontmatterLineIsMalformed() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                : missing-key
                ---
                # Body""");

        assertEquals(Optional.empty(), frontmatter.string("publicId"));
        assertEquals("""
                ---
                : missing-key
                ---
                # Body""", frontmatter.body());
    }

    @Test
    void bodyIsEmptyWhenNothingFollowsTheClosingDelimiter() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicId: my-essay
                ---""");

        assertEquals("", frontmatter.body());
    }

    @Test
    void bodyPreservesCrLfLineEndingsByteForByte() {
        String source = "---\r\npublicId: my-essay\r\n---\r\n# My Essay\r\nPlain prose.\r\n";

        Frontmatter frontmatter = Frontmatter.parse(source);

        assertEquals("# My Essay\r\nPlain prose.\r\n", frontmatter.body());
    }

    @Test
    void bodyPreservesLoneCrLineEndingsByteForByte() {
        String source = "---\rpublicId: my-essay\r---\r# My Essay\rPlain prose.\r";

        Frontmatter frontmatter = Frontmatter.parse(source);

        assertEquals("# My Essay\rPlain prose.\r", frontmatter.body());
    }

    @Test
    void bodyPreservesAbsenceOfTrailingNewline() {
        String source = "---\npublicId: my-essay\n---\n# My Essay\nPlain prose.";

        Frontmatter frontmatter = Frontmatter.parse(source);

        assertEquals("# My Essay\nPlain prose.", frontmatter.body());
    }

    @Test
    void bodyPreservesMultipleTrailingNewlines() {
        String source = "---\npublicId: my-essay\n---\n# My Essay\n\n\n";

        Frontmatter frontmatter = Frontmatter.parse(source);

        assertEquals("# My Essay\n\n\n", frontmatter.body());
    }
}
