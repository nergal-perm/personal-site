package dev.eugene.publicationexporter.note;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownNoteTest {

    @Test
    void sourceWithScalarReplacesAnExistingKeyInPlace() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publish: true
                workflowStatus: ready_for_review
                publicId: my-essay
                ---
                # Body

                Text.""");

        String updated = frontmatter.sourceWithScalar("workflowStatus", "ready_to_publish");

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
    void sourceWithScalarReplacesOnlyTheExistingValueBytes() {
        String source = "---\nworkflowStatus:    stale   # keep\npublicId: my-essay\n---\nBody.";
        MarkdownNote frontmatter = MarkdownNote.parse(source);

        String updated = frontmatter.sourceWithScalar("workflowStatus", "ready_for_review");

        assertEquals(
                "---\nworkflowStatus:    ready_for_review   # keep\npublicId: my-essay\n---\nBody.",
                updated);
    }

    @Test
    void sourceWithScalarInsertsAnAbsentKeyBeforeTheClosingDelimiter() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publish: true
                publicId: my-essay
                ---
                Body.""");

        String updated = frontmatter.sourceWithScalar("workflowStatus", "not_prepared");

        assertEquals("""
                ---
                publish: true
                publicId: my-essay
                workflowStatus: not_prepared
                ---
                Body.""", updated);
    }

    @Test
    void sourceWithScalarPreservesLineEndingsAndBodyExactly() {
        String source = "---\r\npublish: true\r\n---\r\nBody with trailing space \r\n";
        MarkdownNote frontmatter = MarkdownNote.parse(source);

        String updated = frontmatter.sourceWithScalar("workflowStatus", "stale");

        assertTrue(updated.startsWith("---\r\npublish: true\r\nworkflowStatus: stale\r\n---\r\n"));
        assertTrue(updated.endsWith("Body with trailing space \r\n"));
    }

    @Test
    void sourceWithScalarThrowsOnNoFrontmatterWhenBodyContainsDelimiter() {
        MarkdownNote frontmatter = MarkdownNote.parse("Text\n---\nMore");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> frontmatter.sourceWithScalar("workflowStatus", "ready"));

        assertEquals("sourceWithScalar requires a note with frontmatter already present.", exception.getMessage());
    }

    @Test
    void sourceWithScalarPreservesLoneCrLineEndingsAndBodyExactly() {
        String source = "---\rpublicId: my-essay\r---\r# My Essay\rPlain prose.\r";
        MarkdownNote frontmatter = MarkdownNote.parse(source);

        String updated = frontmatter.sourceWithScalar("workflowStatus", "stale");

        assertEquals("---\rpublicId: my-essay\rworkflowStatus: stale\r---\r# My Essay\rPlain prose.\r",
                updated);
    }

    @Test
    void sourceWithScalarPreservesMixedLineEndingsOutsideTheTouchedLine() {
        String source = "---\r\npublish: true\n---\r\nBody.\n";
        MarkdownNote frontmatter = MarkdownNote.parse(source);

        String updated = frontmatter.sourceWithScalar("workflowStatus", "stale");

        assertEquals("---\r\npublish: true\nworkflowStatus: stale\n---\r\nBody.\n", updated);
    }

    @Test
    void parsesStringValue() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publicId: my-essay
                ---
                # Body""");

        assertEquals(Optional.of("my-essay"), frontmatter.string("publicId"));
    }

    @Test
    void parsesBooleanTrueFlag() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publish: true
                ---
                # Body""");

        assertTrue(frontmatter.flag("publish"));
    }

    @Test
    void missingKeyReturnsEmptyOptional() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publicId: my-essay
                ---
                """);

        assertEquals(Optional.empty(), frontmatter.string("sourceId"));
    }

    @Test
    void missingFlagReturnsFalse() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publicId: my-essay
                ---
                """);

        assertFalse(frontmatter.flag("publish"));
    }

    @Test
    void parsesAnOrderedListOfScalarMaps() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                supports:
                  - label: First claim
                    target: first-claim
                  - label: "Second: claim"
                ---
                """);

        assertEquals(
                List.of(
                        Map.of("label", "First claim", "target", "first-claim"),
                        Map.of("label", "Second: claim")),
                frontmatter.listOfMaps("supports"));
    }

    @Test
    void absentAndExplicitlyEmptyMapListsAreEmpty() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                supports: []
                ---
                """);

        assertEquals(List.of(), frontmatter.listOfMaps("supports"));
        assertEquals(List.of(), frontmatter.listOfMaps("opposes"));
    }

    @Test
    void noOpeningDelimiterYieldsAllValuesAbsent() {
        MarkdownNote frontmatter = MarkdownNote.parse("# Just a body, no frontmatter block");

        assertEquals(Optional.empty(), frontmatter.string("publicId"));
        assertFalse(frontmatter.flag("publish"));
    }

    @Test
    void quotedValueIsUnquoted() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publicId: "my-essay"
                ---
                """);

        assertEquals(Optional.of("my-essay"), frontmatter.string("publicId"));
    }

    @Test
    void quotedTrueIsNotABooleanFlag() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publish: "true"
                ---
                """);

        assertFalse(frontmatter.flag("publish"));
    }

    @Test
    void bareNullIsNotAString() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                sourceId: null
                ---
                """);

        assertEquals(Optional.empty(), frontmatter.string("sourceId"));
    }

    @Test
    void bareBooleanIsNotAString() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                sourceId: true
                ---
                """);

        assertEquals(Optional.empty(), frontmatter.string("sourceId"));
    }

    @Test
    void quotedNullIsAString() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                sourceId: "null"
                ---
                """);

        assertEquals(Optional.of("null"), frontmatter.string("sourceId"));
    }

    @Test
    void duplicateKeyMakesTheWholeBlockUnparseable() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
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
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publicId: my-essay
                ---
                sourceId: this-looks-like-frontmatter-but-is-body-text""");

        assertEquals(Optional.empty(), frontmatter.string("sourceId"));
    }

    @Test
    void nullSourceIsRejectedAtParseTime() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> MarkdownNote.parse(null));
        assertEquals("noteSource", exception.getMessage());
    }

    @Test
    void unterminatedFrontmatterBlockDoesNotParseBodyAsMetadata() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publicId: my-essay
                # Body starts here
                Note: see also the appendix""");

        assertEquals(Optional.empty(), frontmatter.string("publicId"));
        assertEquals(Optional.empty(), frontmatter.string("Note"));
    }

    @Test
    void bodyReturnsTextAfterTheClosingDelimiter() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publicId: my-essay
                ---
                # My Essay

                Plain prose body.""");

        assertEquals("# My Essay\n\nPlain prose body.", frontmatter.body());
    }

    @Test
    void bodyIsTheWholeSourceWhenNoFrontmatterBlockExists() {
        MarkdownNote frontmatter = MarkdownNote.parse("# Just a body, no frontmatter block");

        assertEquals("# Just a body, no frontmatter block", frontmatter.body());
    }

    @Test
    void bodyIsTheWholeSourceWhenAFrontmatterLineIsMalformed() {
        MarkdownNote frontmatter = MarkdownNote.parse("""
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
        MarkdownNote frontmatter = MarkdownNote.parse("""
                ---
                publicId: my-essay
                ---""");

        assertEquals("", frontmatter.body());
    }

    @Test
    void bodyPreservesCrLfLineEndingsByteForByte() {
        String source = "---\r\npublicId: my-essay\r\n---\r\n# My Essay\r\nPlain prose.\r\n";

        MarkdownNote frontmatter = MarkdownNote.parse(source);

        assertEquals("# My Essay\r\nPlain prose.\r\n", frontmatter.body());
    }

    @Test
    void bodyPreservesLoneCrLineEndingsByteForByte() {
        String source = "---\rpublicId: my-essay\r---\r# My Essay\rPlain prose.\r";

        MarkdownNote frontmatter = MarkdownNote.parse(source);

        assertEquals("# My Essay\rPlain prose.\r", frontmatter.body());
    }

    @Test
    void bodyPreservesAbsenceOfTrailingNewline() {
        String source = "---\npublicId: my-essay\n---\n# My Essay\nPlain prose.";

        MarkdownNote frontmatter = MarkdownNote.parse(source);

        assertEquals("# My Essay\nPlain prose.", frontmatter.body());
    }

    @Test
    void bodyPreservesMultipleTrailingNewlines() {
        String source = "---\npublicId: my-essay\n---\n# My Essay\n\n\n";

        MarkdownNote frontmatter = MarkdownNote.parse(source);

        assertEquals("# My Essay\n\n\n", frontmatter.body());
    }
}
