package dev.eugene.publicationexporter.prepare;

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
    void emptyToNonEmptyIsAllAdded() {
        RussianDiff diff = RussianDiff.betweenBodies("", "new line");

        assertFalse(diff.isEmpty());
        assertEquals(List.of(new RussianDiff.Line(RussianDiff.LineKind.ADDED, "new line")), diff.lines());
    }
}
