package dev.eugene.publicationexporter.prepare;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        Map<Integer, String> scanned = OccurrenceLabelMarkers.scan(delimited);

        assertEquals(Map.of(0, "Grandpa Shvedov", 1, "public essay"), scanned);
    }

    @Test
    void scanReturnsEmptyMapWhenNoDelimitersPresent() {
        assertEquals(Map.of(), OccurrenceLabelMarkers.scan("Plain prose, no markers."));
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
