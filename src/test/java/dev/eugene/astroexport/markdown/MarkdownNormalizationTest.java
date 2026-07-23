package dev.eugene.astroexport.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class MarkdownNormalizationTest {
  @Test
  void ignoresHeadingsInsideProtectedContexts() {
    for (String body : List.of(
        "```markdown\n## Definition\n\nCode sample.\n```\n",
        "`Code sample.\n## Definition\n\nNot rendered.\n`\n",
        "<pre>\n## Definition\n\nNot rendered.\n</pre>\n",
        "<!--\n## Definition\n\nHidden.\n-->\n",
        "%%\n## Definition\n\nHidden.\n%%\n")) {
      assertTrue(MarkdownScanner.section(body, "Definition").isEmpty(), body);
    }
  }

  @Test
  void rejectsHeadingsSplitAcrossPhysicalLines() {
    assertTrue(MarkdownScanner.section("##\nDefinition\n\nHidden.\n", "Definition").isEmpty());
  }

  @Test
  void preservesProtectedContentWithinARealSection() {
    String body = "## Definition\n\nPublic definition.\n\n```markdown\n## Example heading\n```\n\n"
        + "<!-- ## Hidden boundary -->\n\nStill part.\n\n## Context\n\nMore text.\n";

    assertEquals("Public definition.\n\n```markdown\n## Example heading\n```\n\n"
        + "<!-- ## Hidden boundary -->\n\nStill part.", MarkdownScanner.section(body, "Definition").orElseThrow());
  }

  @Test
  void resumesAfterProtectedContextsContainingUnclosedSyntax() {
    assertEquals("Public definition.", MarkdownScanner.section("<pre>\n```\n</pre>\n\n## Definition\n\nPublic definition.\n", "Definition").orElseThrow());
    assertEquals("Public definition.", MarkdownScanner.section("%%\n<pre>\n%%\n\n## Definition\n\nPublic definition.\n", "Definition").orElseThrow());
  }

  @Test
  void stripsOnlyObsidanCommentsAndKeepsCommentMarkersInProtectedContexts() {
    assertEquals("Visible.\n\nAfter.", MarkdownScanner.stripObsidianComments("Visible.\n%% hidden %%\nAfter."));
    assertEquals("```md\n%% literal %%\n```\n`%% literal %%`", MarkdownScanner.stripObsidianComments("```md\n%% literal %%\n```\n`%% literal %%`"));
  }

  @Test
  void recognisesFencesWithCarriageReturnLineEndings() {
    String body = "~~~md\r## Hidden\r~~~\r## Definition\r\rVisible.\r";
    assertEquals("Visible.", MarkdownScanner.section(body, "Definition").orElseThrow());
  }

  @Test
  void extractsListItemsFromAVisibleSection() {
    assertEquals(List.of("first", "nested", "second"), MarkdownScanner.listItems("## Items\n\n- first\n  - nested\n- second\n\n## Other\n", "Items"));
    assertFalse(MarkdownScanner.listItems("```md\n## Items\n- hidden\n```", "Items").contains("hidden"));
  }
}
