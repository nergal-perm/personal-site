package dev.eugene.astroexport.translation;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import java.util.List;

/** Line- and paragraph-level diffs between two revisions of translation source or output text. */
public final class TranslationDiff {
  private static final int CONTEXT_LINES = 2;

  private TranslationDiff() { }

  /** A unified diff of {@code current} against {@code previous}, or "" when they are identical. */
  public static String unifiedDiff(String previous, String current) {
    List<String> previousLines = previous.lines().toList();
    List<String> currentLines = current.lines().toList();
    Patch<String> patch = DiffUtils.diff(previousLines, currentLines);
    if (patch.getDeltas().isEmpty()) {
      return "";
    }
    List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
        "published", "current", previousLines, patch, CONTEXT_LINES);
    return String.join("\n", unified);
  }

  /** Count of paragraphs (blank-line-delimited blocks) that differ between the two revisions. */
  public static int changedParagraphCount(String previous, String current) {
    Patch<String> patch = DiffUtils.diff(paragraphs(previous), paragraphs(current));
    return patch.getDeltas().size();
  }

  private static List<String> paragraphs(String text) {
    return List.of(text.strip().split("\\n\\s*\\n"));
  }
}
