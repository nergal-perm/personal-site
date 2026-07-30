package dev.eugene.astroexport.references;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

final class SemanticReferenceMarkdownTest {

  @Test
  void capturesRepeatedLabelsAndTargets() {
    String markdown = "[first](ref:alpha) [second](ref:beta) and [again](ref:alpha)";
    List<SemanticReferenceMarkdown.Occurrence> occurrences = SemanticReferenceMarkdown.occurrences(markdown);

    assertEquals(List.of("alpha", "beta", "alpha"), occurrences.stream().map(SemanticReferenceMarkdown.Occurrence::id).toList());
    assertEquals(List.of("first", "second", "again"), occurrences.stream().map(SemanticReferenceMarkdown.Occurrence::label).toList());
  }

  @Test
  void preservesDifferentHeadingFragmentsDuringParsing() {
    String markdown = "[first](ref:alpha#My Heading) [second](ref:beta#Another_Heading)";
    List<SemanticReferenceMarkdown.Occurrence> occurrences = SemanticReferenceMarkdown.occurrences(markdown);

    assertEquals("#my-heading", occurrences.get(0).heading());
    assertEquals("#another-heading", occurrences.get(1).heading());
  }

  @Test
  void ignoresEscapedLinksAndProtectedMarkdown() {
    String markdown = "\\[skipped](ref:alpha) [first](ref:alpha) `\\[ignored](ref:alpha)` ```md\n[ignored](ref:beta)\n```";
    String projected = SemanticReferenceMarkdown.project(markdown, map(), mapTo(""));

    assertEquals("\\[skipped](ref:alpha) [first](/alpha#one) `\\[ignored](ref:alpha)` ```md\n[ignored](ref:beta)\n```", projected);
  }

  @Test
  void projectsRepeatedTargetsAndSupportsHeadingFragments() {
    String markdown = "[first](ref:alpha#One) [second](ref:beta#Two) [same](ref:alpha#Three)";
    String projected = SemanticReferenceMarkdown.project(markdown, map(), mapTo(""));

    assertEquals("[first](/alpha#one) [second](/beta#two) [same](/alpha#one)", projected);
  }

  @Test
  void supportsResolverThatReturnsEmpty() {
    String markdown = "[hidden](ref:alpha) [visible](ref:beta)";
    String projected = SemanticReferenceMarkdown.project(markdown, map(), ignored -> Optional.empty());

    assertEquals("hidden visible", projected);
  }

  @Test
  void failsForMissingReferenceId() {
    PageReferenceMap missing = new PageReferenceMap(
        PageReferenceMap.SCHEMA_VERSION,
        "page",
        "blog/A.md",
        "0",
        "0",
        List.of("alpha"),
        Map.of("alpha", new PageReferenceMap.Reference("/alpha", "Alpha", "#one")));
    String markdown = "[missing](ref:gamma)";

    assertThrows(
        IllegalArgumentException.class,
        () -> SemanticReferenceMarkdown.project(markdown, missing, mapTo("")));
  }

  @Test
  void ignoresProtectedHtmlAndPreSpans() {
    String markdown = "before <!-- [skip](ref:alpha) -->\n<pre>\n[skip2](ref:beta)\n</pre>\n[go](ref:beta)";
    String projected = SemanticReferenceMarkdown.project(markdown, map(), mapTo(""));

    assertEquals("before <!-- [skip](ref:alpha) -->\n<pre>\n[skip2](ref:beta)\n</pre>\n[go](/beta#two)", projected);
  }

  @Test
  void normalizesHeadingFragmentInputs() {
    assertEquals("#my-heading", SemanticReferenceMarkdown.normalizeHeadingFragment("#My Heading"));
    assertEquals("#another-heading", SemanticReferenceMarkdown.normalizeHeadingFragment("#Another Heading"));
    assertEquals("", SemanticReferenceMarkdown.normalizeHeadingFragment("plain"));
  }

  private static PageReferenceMap map() {
    return new PageReferenceMap(
        PageReferenceMap.SCHEMA_VERSION,
        "page",
        "blog/A.md",
        "0",
        "0",
        List.of("alpha", "beta"),
        Map.of(
            "alpha", new PageReferenceMap.Reference("/alpha", "Alpha", "#one"),
            "beta", new PageReferenceMap.Reference("/beta", "Beta", "#two")));
  }

  private static Function<PageReferenceMap.Reference, Optional<String>> mapTo(String prefix) {
    return ref -> Optional.of(prefix + ref.targetRef() + ref.heading());
  }
}
