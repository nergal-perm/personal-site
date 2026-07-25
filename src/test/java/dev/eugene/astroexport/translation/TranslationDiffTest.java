package dev.eugene.astroexport.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TranslationDiffTest {
  @Test
  void unifiedDiffIsEmptyWhenTextsAreIdentical() {
    String text = "Line one.\nLine two.\n";
    assertEquals("", TranslationDiff.unifiedDiff(text, text));
  }

  @Test
  void unifiedDiffContainsChangedLineMarkers() {
    String previous = "Paragraph one.\n\nParagraph two.\n";
    String current = "Paragraph one changed.\n\nParagraph two.\n";
    String diff = TranslationDiff.unifiedDiff(previous, current);
    assertTrue(diff.contains("-Paragraph one."));
    assertTrue(diff.contains("+Paragraph one changed."));
    assertTrue(diff.contains("Paragraph two."));
  }

  @Test
  void changedParagraphCountIsZeroForIdenticalText() {
    String text = "Paragraph one.\n\nParagraph two.\n\nParagraph three.\n";
    assertEquals(0, TranslationDiff.changedParagraphCount(text, text));
  }

  @Test
  void changedParagraphCountCountsOnlyModifiedParagraphs() {
    String previous = "Paragraph one.\n\nParagraph two.\n\nParagraph three.\n";
    String current = "Paragraph one.\n\nParagraph two edited.\n\nParagraph three.\n";
    assertEquals(1, TranslationDiff.changedParagraphCount(previous, current));
  }

  @Test
  void changedParagraphCountCountsInsertedParagraphsToo() {
    String previous = "Paragraph one.\n\nParagraph two.\n";
    String current = "Paragraph one.\n\nParagraph two.\n\nParagraph three is new.\n";
    assertEquals(1, TranslationDiff.changedParagraphCount(previous, current));
  }
}
