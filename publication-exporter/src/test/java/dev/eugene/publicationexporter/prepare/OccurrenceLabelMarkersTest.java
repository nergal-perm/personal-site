package dev.eugene.publicationexporter.prepare;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OccurrenceLabelMarkersTest {

    @Test
    void delimitWrapsEachOccurrenceLabelSpan() {
        String resolvedBody = "See дед Шведов here.";
        LinkOccurrence occurrence = new LinkOccurrence("grandpa-shvedov", "дед Шведов", Optional.empty(), 4, 14);

        String delimited = OccurrenceLabelMarkers.delimit(resolvedBody, List.of(occurrence));

        assertEquals("See " + OccurrenceLabelMarkers.openMarker(0) + "дед Шведов"
                + OccurrenceLabelMarkers.closeMarker(0) + " here.", delimited);
    }

    @Test
    void delimitWrapsMultipleOccurrencesWithoutShiftingLaterSpans() {
        String resolvedBody = "A then B.";
        LinkOccurrence first = new LinkOccurrence("a", "A", Optional.empty(), 0, 1);
        LinkOccurrence second = new LinkOccurrence("b", "B", Optional.empty(), 7, 8);

        String delimited = OccurrenceLabelMarkers.delimit(resolvedBody, List.of(first, second));

        assertEquals(OccurrenceLabelMarkers.openMarker(0) + "A"
                + OccurrenceLabelMarkers.closeMarker(0) + " then "
                + OccurrenceLabelMarkers.openMarker(1) + "B"
                + OccurrenceLabelMarkers.closeMarker(1) + ".", delimited);
    }

    @Test
    void scanRecoversDelimitedSpanContentsByIndexWhenSpansArePhysicallyReordered() {
        String delimited = "First " + OccurrenceLabelMarkers.openMarker(1) + "public essay"
                + OccurrenceLabelMarkers.closeMarker(1) + ", then "
                + OccurrenceLabelMarkers.openMarker(0) + "Grandpa Shvedov"
                + OccurrenceLabelMarkers.closeMarker(0) + ".";

        OccurrenceLabelMarkers.ScanResult scanned = OccurrenceLabelMarkers.scan(delimited);

        assertEquals(Map.of(0, "Grandpa Shvedov", 1, "public essay"), scanned.spans());
        assertFalse(scanned.malformed());
    }

    @Test
    void scanReturnsEmptyMapWhenNoDelimitersPresent() {
        OccurrenceLabelMarkers.ScanResult scanned = OccurrenceLabelMarkers.scan("Plain prose, no markers.");

        assertEquals(Map.of(), scanned.spans());
        assertFalse(scanned.malformed());
    }

    @Test
    void scanOmitsAnIndexThatAppearsMoreThanOnceAndFlagsMalformed() {
        String delimited = OccurrenceLabelMarkers.openMarker(0) + "first copy"
                + OccurrenceLabelMarkers.closeMarker(0) + " and "
                + OccurrenceLabelMarkers.openMarker(0) + "second copy"
                + OccurrenceLabelMarkers.closeMarker(0) + ", then "
                + OccurrenceLabelMarkers.openMarker(1) + "unique"
                + OccurrenceLabelMarkers.closeMarker(1) + ".";

        OccurrenceLabelMarkers.ScanResult scanned = OccurrenceLabelMarkers.scan(delimited);

        assertEquals(Map.of(1, "unique"), scanned.spans());
        assertTrue(scanned.malformed());
    }

    @Test
    void scanFlagsMalformedForAnOrphanCloseMarkerWithNoMatchingOpen() {
        String delimited = OccurrenceLabelMarkers.closeMarker(0) + "Before "
                + OccurrenceLabelMarkers.openMarker(0) + "Target EN" + OccurrenceLabelMarkers.closeMarker(0);

        OccurrenceLabelMarkers.ScanResult scanned = OccurrenceLabelMarkers.scan(delimited);

        assertEquals(Map.of(0, "Target EN"), scanned.spans());
        assertTrue(scanned.malformed());
    }

    @Test
    void scanFlagsMalformedWhenDuplicateInventedIndicesCancelOutToNothing() {
        String delimited = OccurrenceLabelMarkers.openMarker(0) + "A" + OccurrenceLabelMarkers.closeMarker(0)
                + OccurrenceLabelMarkers.openMarker(1) + "B1" + OccurrenceLabelMarkers.closeMarker(1)
                + OccurrenceLabelMarkers.openMarker(1) + "B2" + OccurrenceLabelMarkers.closeMarker(1);

        OccurrenceLabelMarkers.ScanResult scanned = OccurrenceLabelMarkers.scan(delimited);

        assertEquals(Map.of(0, "A"), scanned.spans());
        assertTrue(scanned.malformed());
    }

    @Test
    void scanFlagsMalformedWhenAnotherMarkerIsNestedInsideAnOccurrenceSpan() {
        String delimited = OccurrenceLabelMarkers.openMarker(0) + "A "
                + OccurrenceLabelMarkers.openMarker(1) + "B" + OccurrenceLabelMarkers.closeMarker(1)
                + OccurrenceLabelMarkers.closeMarker(0);

        OccurrenceLabelMarkers.ScanResult scanned = OccurrenceLabelMarkers.scan(delimited);

        assertEquals(Map.of(1, "B"), scanned.spans());
        assertTrue(scanned.malformed());
    }

    @Test
    void scanStaysMalformedEvenWhenALaterCleanPairReplacesTheRejectedIndex() {
        // A rejected (nested) open(0) is followed later by a completely separate, well-formed
        // open(0)/close(0) pair. Taken in isolation the recovered spans map could look complete
        // for assigned indices {0, 1} — but the malformed flag must still be set, so the caller
        // doesn't let this "launder" the earlier corruption into an apparently-clean result.
        String delimited = OccurrenceLabelMarkers.openMarker(0) + "A "
                + OccurrenceLabelMarkers.openMarker(1) + "B" + OccurrenceLabelMarkers.closeMarker(1)
                + OccurrenceLabelMarkers.closeMarker(0)
                + OccurrenceLabelMarkers.openMarker(0) + "C" + OccurrenceLabelMarkers.closeMarker(0);

        OccurrenceLabelMarkers.ScanResult scanned = OccurrenceLabelMarkers.scan(delimited);

        assertEquals(Map.of(0, "C", 1, "B"), scanned.spans());
        assertTrue(scanned.malformed());
    }

    @Test
    void stripRemovesDelimitersButKeepsContent() {
        String delimited = "As he wrote " + OccurrenceLabelMarkers.openMarker(0) + "Grandpa Shvedov"
                + OccurrenceLabelMarkers.closeMarker(0) + " today.";

        String stripped = OccurrenceLabelMarkers.strip(delimited);

        assertEquals("As he wrote Grandpa Shvedov today.", stripped);
    }

    @Test
    void containsReservedCharactersRecognizesBothMarkerSubranges() {
        assertEquals(false, OccurrenceLabelMarkers.containsReservedCharacters("Plain prose."));
        assertEquals(true, OccurrenceLabelMarkers.containsReservedCharacters(
                "Before " + OccurrenceLabelMarkers.openMarker(0) + " after"));
        assertEquals(true, OccurrenceLabelMarkers.containsReservedCharacters(
                "Before " + OccurrenceLabelMarkers.closeMarker(OccurrenceLabelMarkers.MAX_OCCURRENCES - 1)
                        + " after"));
    }
}
