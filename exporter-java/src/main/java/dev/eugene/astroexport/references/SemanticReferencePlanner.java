package dev.eugene.astroexport.references;

import dev.eugene.astroexport.markdown.MarkdownScanner;
import dev.eugene.astroexport.validation.PublicationDiagnostic;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a stable Markdown projection and semantic occurrence plan for one page.
 */
public final class SemanticReferencePlanner {
  private static final Pattern WIKILINK = Pattern.compile(
      "(!?)\\[\\[([^\\]|#]+)(#[^\\]|*]*)?(?:\\|([^\\]]+))?\\]\\]");
  private static final Pattern TIMESTAMP = Pattern.compile("^\\d{12}\\s+");

  public PreparedSemanticBody prepare(
      String sourcePath,
      String pageRef,
      String body,
      Optional<PageReferenceMap> previous,
      VaultReferenceResolver resolver) {
    PreviousState previousState = PreviousState.from(previous);
    List<Occurrence> occurrences = parse(body);

    StringBuilder markdown = new StringBuilder(body.length());
    int cursor = 0;
    List<PublicationDiagnostic> diagnostics = new ArrayList<>();
    List<String> order = new ArrayList<>();
    Map<String, PageReferenceMap.Reference> references = new LinkedHashMap<>();
    Set<String> used = new LinkedHashSet<>();
    NextReference nextReference = new NextReference(nextReferenceNumber(previous));

    for (Occurrence occurrence : occurrences) {
      markdown.append(body, cursor, occurrence.start());
      markdown.append(rewrite(
          sourcePath,
          occurrence,
          resolver,
          previousState,
          used,
          diagnostics,
          references,
          order,
          nextReference));
      cursor = occurrence.end();
    }
    markdown.append(body.substring(cursor));

    return new PreparedSemanticBody(
        markdown.toString(),
        new ReferencePlan(pageRef, sourcePath, List.copyOf(order), Map.copyOf(references)),
        List.copyOf(diagnostics));
  }

  private static String rewrite(
      String sourcePath,
      Occurrence occurrence,
      VaultReferenceResolver resolver,
      PreviousState previousState,
      Set<String> used,
      List<PublicationDiagnostic> diagnostics,
      Map<String, PageReferenceMap.Reference> references,
      List<String> order,
      NextReference nextReference) {

    if (occurrence.embed()) {
      return occurrence.source();
    }

    VaultReferenceResolver.Resolution resolution = resolver.resolve(sourcePath, occurrence.target());
    if (resolution.status() == VaultReferenceResolver.Status.UNRESOLVED) {
      diagnostics.add(new PublicationDiagnostic(
          "unresolved-reference",
          sourcePath + ": unresolved target " + occurrence.target(),
          false));
      return occurrence.label();
    }
    if (resolution.status() == VaultReferenceResolver.Status.AMBIGUOUS) {
      throw new ReferencePlanningException(
          "ambiguous-reference-target",
          sourcePath + ": ambiguous target " + occurrence.target());
    }

    String authoredTarget = occurrence.target() + (occurrence.heading() == null ? "" : occurrence.heading());
    String normalizedHeading = normalizeHeading(occurrence.heading());
    String signature = signature(
        resolution.pageRef(), authoredTarget, normalizedHeading, occurrence.label());

    ReuseDecision decision = previousState.reuse(signature, occurrence.sequenceIndex());
    if (decision.blocking()) {
      diagnostics.add(new PublicationDiagnostic(
          "reference-reconciliation-required",
          sourcePath + ": ambiguous old occurrence assignment for " + occurrence.target(),
          true));
    }

    String refId = decision.referenceId();
    if (refId == null || used.contains(refId)) {
      do {
        refId = nextReference.next();
      } while (used.contains(refId));
    }

    used.add(refId);
    references.put(refId, new PageReferenceMap.Reference(
        resolution.pageRef(),
        authoredTarget,
        occurrence.heading(),
        occurrence.label()));
    order.add(refId);
    return "[" + occurrence.label() + "](ref:" + refId + ")";
  }

  private static String signature(String targetRef, String authoredTarget, String heading, String label) {
    return targetRef + "|" + (authoredTarget == null ? "" : authoredTarget) + "|" + (heading == null ? ""
        : heading) + "|" + (label == null ? "" : label);
  }

  private static List<Occurrence> parse(String body) {
    List<Occurrence> occurrences = new ArrayList<>();
    Matcher matcher = WIKILINK.matcher(body);
    List<MarkdownScanner.Span> spans = MarkdownScanner.protectedSpans(body);
    int sequence = 0;

    while (matcher.find()) {
      if (isEscaped(body, matcher.start())) {
        continue;
      }
      if (insideProtected(matcher.start(), matcher.end(), spans)) {
        continue;
      }
      String target = matcher.group(2);
      String heading = matcher.group(3) == null ? "" : matcher.group(3);
      String label = matcher.group(4) == null
          ? defaultLabel(target)
          : matcher.group(4).strip();
      boolean embed = !matcher.group(1).isEmpty();
      occurrences.add(new Occurrence(
          matcher.start(),
          matcher.end(),
          embed,
          target.strip(),
          heading,
          label,
          matcher.group(),
          sequence++));
    }
    return occurrences;
  }

  private static boolean insideProtected(int start, int end, List<MarkdownScanner.Span> spans) {
    for (MarkdownScanner.Span span : spans) {
      if (span.end() <= start) {
        continue;
      }
      if (span.start() >= end) {
        return false;
      }
      return true;
    }
    return false;
  }

  private static boolean isEscaped(String body, int index) {
    int cursor = index - 1;
    int escapes = 0;
    while (cursor >= 0 && body.charAt(cursor) == '\\') {
      escapes++;
      cursor--;
    }
    return (escapes & 1) == 1;
  }

  private static String defaultLabel(String target) {
    String fallback = target;
    try {
      fallback = Path.of(target).getFileName().toString();
    } catch (RuntimeException ignored) {
      // keep original text if not path-like.
    }
    fallback = fallback.replaceFirst("\\.md$", "");
    return TIMESTAMP.matcher(fallback).replaceFirst("").strip();
  }

  private static String normalizeHeading(String heading) {
    return SemanticReferenceMarkdown.normalizeHeadingFragment(heading == null ? "" : heading);
  }

  private static final class PreviousState {
    private final Map<Integer, String> order;
    private final Map<String, String> signatureById;
    private final Map<String, List<String>> idsBySignature;

    private PreviousState(
        Map<Integer, String> order,
        Map<String, String> signatureById,
        Map<String, List<String>> idsBySignature) {
      this.order = order;
      this.signatureById = signatureById;
      this.idsBySignature = idsBySignature;
    }

    static PreviousState from(Optional<PageReferenceMap> previous) {
      if (previous.isEmpty()) {
        return new PreviousState(new HashMap<>(), new HashMap<>(), new HashMap<>());
      }
      PageReferenceMap map = previous.get();
      Map<Integer, String> order = new HashMap<>();
      Map<String, String> signatureById = new HashMap<>();
      Map<String, List<String>> idsBySignature = new HashMap<>();

      List<String> ids = map.order();
      for (int index = 0; index < ids.size(); index++) {
        String id = ids.get(index);
        PageReferenceMap.Reference reference = map.references().get(id);
        if (reference == null) {
          continue;
        }
        String signature = signature(
            reference.targetRef(),
            reference.authoredTarget() == null ? "" : reference.authoredTarget(),
            normalizeHeading(reference.heading()),
            reference.label() == null ? "" : reference.label());
        order.put(index, id);
        signatureById.put(id, signature);
        idsBySignature.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(id);
      }
      return new PreviousState(order, signatureById, idsBySignature);
    }

    ReuseDecision reuse(String signature, int sequenceIndex) {
      String byIndex = order.get(sequenceIndex);
      if (byIndex != null && signature.equals(signatureById.get(byIndex))) {
        // Position is a stable discriminator for unchanged repeated occurrences.
        remove(byIndex, signature);
        return new ReuseDecision(byIndex, false);
      }

      List<String> candidates = idsBySignature.getOrDefault(signature, new ArrayList<>());
      if (candidates.size() == 1) {
        String selected = candidates.remove(0);
        remove(selected, signature);
        return new ReuseDecision(selected, false);
      }
      if (candidates.size() > 1) {
        return new ReuseDecision(null, true);
      }
      return new ReuseDecision(null, false);
    }

    private void remove(String id, String signature) {
      order.values().removeIf(id::equals);
      List<String> candidates = idsBySignature.get(signature);
      if (candidates != null) {
        candidates.remove(id);
        if (candidates.isEmpty()) {
          idsBySignature.remove(signature);
        }
      }
      signatureById.remove(id);
    }
  }

  private static final class ReuseDecision {
    private final String referenceId;
    private final boolean blocking;

    ReuseDecision(String referenceId, boolean blocking) {
      this.referenceId = referenceId;
      this.blocking = blocking;
    }

    String referenceId() {
      return referenceId;
    }

    boolean blocking() {
      return blocking;
    }
  }

  public static int nextReferenceNumber(Optional<PageReferenceMap> previous) {
    if (previous.isEmpty()) {
      return 1;
    }
    int next = 1;
    for (String id : previous.get().order()) {
      if (!id.startsWith("ref-")) {
        continue;
      }
      try {
        next = Math.max(next, Integer.parseInt(id.substring("ref-".length())) + 1);
      } catch (NumberFormatException ignored) {
        // Ignore malformed ids.
      }
    }
    return next;
  }

  public static final record PreparedSemanticBody(
      String markdown,
      ReferencePlan plan,
      List<PublicationDiagnostic> diagnostics) {
  }

  private static final record Occurrence(
      int start,
      int end,
      boolean embed,
      String target,
      String heading,
      String label,
      String source,
      int sequenceIndex) {
  }

  private static final class NextReference {
    private int value;

    NextReference(int value) {
      this.value = value;
    }

    String next() {
      return "ref-" + String.format("%04d", value++);
    }
  }
}
