package dev.eugene.publicationexporter.note;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontmatterTest {

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
}
