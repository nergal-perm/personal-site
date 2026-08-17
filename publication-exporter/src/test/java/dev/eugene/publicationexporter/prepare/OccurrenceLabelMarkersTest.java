package dev.eugene.publicationexporter.prepare;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OccurrenceLabelMarkersTest {

    @Test
    void delimitWrapsEachOccurrenceLabelSpan() {
        String resolvedBody = "See дед Шведов here.";
        LinkOccurrence occurrence = new LinkOccurrence("grandpa-shvedov", "дед Шведов", Optional.empty(), 4, 14);

        String delimited = OccurrenceLabelMarkers.delimit(resolvedBody, List.of(occurrence));

        assertEquals("See дед Шведов here.", delimited);
    }

    @Test
    void delimitWrapsMultipleOccurrencesWithoutShiftingLaterSpans() {
        String resolvedBody = "A then B.";
        LinkOccurrence first = new LinkOccurrence("a", "A", Optional.empty(), 0, 1);
        LinkOccurrence second = new LinkOccurrence("b", "B", Optional.empty(), 7, 8);

        String delimited = OccurrenceLabelMarkers.delimit(resolvedBody, List.of(first, second));

        assertEquals("A then B.", delimited);
    }

    @Test
    void scanRecoversDelimitedSpanContentsInOrder() {
        String delimited = "As he wrote Grandpa Shvedov and referenced public essay.";

        List<String> scanned = OccurrenceLabelMarkers.scan(delimited);

        assertEquals(List.of("Grandpa Shvedov", "public essay"), scanned);
    }

    @Test
    void scanReturnsEmptyListWhenNoDelimitersPresent() {
        assertEquals(List.of(), OccurrenceLabelMarkers.scan("Plain prose, no markers."));
    }

    @Test
    void stripRemovesDelimitersButKeepsContent() {
        String delimited = "As he wrote Grandpa Shvedov today.";

        String stripped = OccurrenceLabelMarkers.strip(delimited);

        assertEquals("As he wrote Grandpa Shvedov today.", stripped);
    }
}
