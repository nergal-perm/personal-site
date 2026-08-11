package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RussianDiffTest {

    @Test
    void identicalTextIsEmptyDiff() {
        RussianDiff diff = RussianDiff.betweenBodies("line one\nline two", "line one\nline two");

        assertTrue(diff.isEmpty());
        assertEquals(
                List.of(new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "line one"),
                        new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "line two")),
                diff.lines());
    }

    @Test
    void appendedLineIsReportedAsAdded() {
        RussianDiff diff = RussianDiff.betweenBodies("line one", "line one\nline two");

        assertFalse(diff.isEmpty());
        assertEquals(
                List.of(new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "line one"),
                        new RussianDiff.Line(RussianDiff.LineKind.ADDED, "line two")),
                diff.lines());
    }

    @Test
    void removedLineIsReportedAsRemoved() {
        RussianDiff diff = RussianDiff.betweenBodies("line one\nline two", "line one");

        assertFalse(diff.isEmpty());
        assertEquals(
                List.of(new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "line one"),
                        new RussianDiff.Line(RussianDiff.LineKind.REMOVED, "line two")),
                diff.lines());
    }

    @Test
    void middleLineChangedIsRemovedThenAdded() {
        RussianDiff diff = RussianDiff.betweenBodies(
                "one\ntwo\nthree", "one\nCHANGED\nthree");

        assertFalse(diff.isEmpty());
        assertEquals(
                List.of(new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "one"),
                        new RussianDiff.Line(RussianDiff.LineKind.REMOVED, "two"),
                        new RussianDiff.Line(RussianDiff.LineKind.ADDED, "CHANGED"),
                        new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "three")),
                diff.lines());
    }

    @Test
    void trailingWhitespaceOnlyChangeIsEmptyAfterNormalization() {
        RussianDiff diff = RussianDiff.betweenBodies("line one  \nline two", "line one\nline two");

        assertTrue(diff.isEmpty());
    }

    @Test
    void crlfLineEndingsNormalizeLikeLineFeed() {
        RussianDiff diff = RussianDiff.betweenBodies("line one\r\nline two", "line one\r\nline two changed");

        assertFalse(diff.isEmpty());
        assertEquals(
                List.of(new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "line one"),
                        new RussianDiff.Line(RussianDiff.LineKind.REMOVED, "line two"),
                        new RussianDiff.Line(RussianDiff.LineKind.ADDED, "line two changed")),
                diff.lines());
    }

    @Test
    void identicalInputsProduceEqualRussianDiffs() {
        RussianDiff first = RussianDiff.betweenBodies("line one\nline two", "line one\nline two");
        RussianDiff second = RussianDiff.betweenBodies("line one\nline two", "line one\nline two");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void emptyToNonEmptyIsAllAdded() {
        RussianDiff diff = RussianDiff.betweenBodies("", "new line");

        assertFalse(diff.isEmpty());
        assertEquals(List.of(new RussianDiff.Line(RussianDiff.LineKind.ADDED, "new line")), diff.lines());
    }

    @Test
    void titleOnlyChangeMakesCompleteDiffNonEmpty() {
        RussianDiff diff = RussianDiff.between(
                "same body", List.of(PublicField.of("title", "Old title"),
                        PublicField.of("description", "same description")),
                "same body", List.of(PublicField.of("title", "New title"),
                        PublicField.of("description", "same description")));

        assertFalse(diff.isEmpty());
        assertEquals(List.of(
                new RussianDiff.Line(RussianDiff.LineKind.REMOVED, "title: Old title"),
                new RussianDiff.Line(RussianDiff.LineKind.ADDED, "title: New title"),
                new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "same body")), diff.lines());
    }

    @Test
    void descriptionOnlyChangeMakesCompleteDiffNonEmpty() {
        RussianDiff diff = RussianDiff.between(
                "same body", List.of(PublicField.of("title", "same title"),
                        PublicField.of("description", "Old description")),
                "same body", List.of(PublicField.of("title", "same title"),
                        PublicField.of("description", "New description")));

        assertFalse(diff.isEmpty());
        assertEquals(List.of(
                new RussianDiff.Line(RussianDiff.LineKind.REMOVED, "description: Old description"),
                new RussianDiff.Line(RussianDiff.LineKind.ADDED, "description: New description"),
                new RussianDiff.Line(RussianDiff.LineKind.UNCHANGED, "same body")), diff.lines());
    }
}
