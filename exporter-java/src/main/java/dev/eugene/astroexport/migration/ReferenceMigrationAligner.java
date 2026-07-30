package dev.eugene.astroexport.migration;

import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.Classification.AMBIGUOUS_TRANSLATION;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.Classification.EXACT;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.Classification.ORDER_MISMATCH;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.Classification.UNRESOLVED_TARGET;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.Classification.UNSAFE_INPUT;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.PageStatus.CONFIRMED_NEEDED;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.PageStatus.EXACT_PAGE;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.PageStatus.ORDER_MISMATCH_PAGE;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.PageStatus.UNRESOLVED_PAGE;
import static dev.eugene.astroexport.migration.ReferenceMigrationAligner.PageStatus.UNSAFE_PAGE;

import dev.eugene.astroexport.markdown.MarkdownScanner;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.VaultReferenceResolver;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only alignment of raw legacy wikilinks against approved RU/EN bytes. */
public final class ReferenceMigrationAligner {
  private static final Pattern WIKILINK = Pattern.compile(
      "(!?)\\[\\[([^\\]|#]+)(#[^\\]|*]*)?(?:\\|([^\\]]+))?\\]\\]");
  private static final Pattern MARKDOWN_LINK = Pattern.compile(
      "\\[(?<label>[^\\]\\n]*?)\\]\\((?<destination>[^\\r\\n)]*?)\\)");
  private static final Pattern TIMESTAMP = Pattern.compile("^\\d{12}\\s+");

  public MigrationPage align(
      RawPage raw,
      ApprovedDocument approvedRussian,
      ApprovedDocument approvedEnglish,
      VaultReferenceResolver resolver) {
    List<RawOccurrence> rawOccurrences = parseRaw(raw.markdown());
    if (!approvedRussian.safe() || !approvedEnglish.safe()) {
      return unsafePage(raw, approvedRussian, approvedEnglish, rawOccurrences,
          approvedRussian.safe() ? approvedEnglish.unsafeReason() : approvedRussian.unsafeReason());
    }

    List<ApprovedSpan> russianSpans = parseApproved(approvedRussian.text(), "ru");
    List<ApprovedSpan> englishSpans = parseApproved(approvedEnglish.text(), "en");
    List<WorkingOccurrence> working = new ArrayList<>();
    Map<String, Integer> duplicateSignatures = duplicateSignatures(rawOccurrences, resolver);

    for (RawOccurrence occurrence : rawOccurrences) {
      VaultReferenceResolver.Resolution resolution = resolver.resolve(raw.sourcePath(), occurrence.authoredTarget());
      if (resolution.status() != VaultReferenceResolver.Status.RESOLVED) {
        working.add(new WorkingOccurrence(occurrence, resolution, List.of(), List.of(), UNRESOLVED_TARGET,
            "raw target has no unique vault identity"));
        continue;
      }
      List<String> routes = routesFor(resolution.currentPath());
      List<ApprovedSpan> ruCandidates = candidates(russianSpans, occurrence.label(), routes, "ru");
      List<ApprovedSpan> enCandidates = candidates(englishSpans, occurrence.label(), routes, "en");
      boolean duplicate = duplicateSignatures.getOrDefault(signature(resolution, occurrence), 0) > 1;
      Classification classification = classify(ruCandidates, enCandidates, duplicate);
      String reason = reason(classification, ruCandidates, enCandidates, duplicate);
      working.add(new WorkingOccurrence(occurrence, resolution, ruCandidates, enCandidates, classification, reason));
    }

    boolean completeUniqueOrders = uniqueAssignmentCount(working, true) == resolvedCount(working)
        && uniqueAssignmentCount(working, false) == resolvedCount(working);
    boolean orderMismatch = completeUniqueOrders && !documentOrder(working, true).equals(documentOrder(working, false));
    boolean unsafeContext = !orderMismatch && sourceContextDrift(raw.markdown(), approvedRussian.text());
    List<MigrationOccurrence> occurrences = new ArrayList<>();
    for (WorkingOccurrence item : working) {
      Classification classification = item.classification();
      String reason = item.reason();
      if (orderMismatch && classification == EXACT) {
        classification = ORDER_MISMATCH;
        reason = "unique RU and EN assignments have different target order";
      } else if (unsafeContext && classification == EXACT) {
        classification = UNSAFE_INPUT;
        reason = "raw context cannot be proven against approved RU bytes";
      }
      occurrences.add(toOccurrence(raw, approvedRussian, approvedEnglish, item, classification, reason));
    }

    PageStatus status = pageStatus(occurrences);
    return new MigrationPage(
        raw.pageRef(),
        raw.sourcePath(),
        status,
        status == EXACT_PAGE,
        List.copyOf(occurrences),
        approvedRussian,
        approvedEnglish);
  }

  private static MigrationPage unsafePage(
      RawPage raw,
      ApprovedDocument approvedRussian,
      ApprovedDocument approvedEnglish,
      List<RawOccurrence> rawOccurrences,
      String reason) {
    List<MigrationOccurrence> occurrences = new ArrayList<>();
    for (RawOccurrence occurrence : rawOccurrences) {
      occurrences.add(new MigrationOccurrence(
          raw.pageRef() + "/" + referenceId(occurrence.ordinal()),
          UNSAFE_INPUT,
          occurrence.source(),
          context(raw.markdown(), occurrence.start(), occurrence.end()),
          approvedRussian.safe() ? null : approvedRussian.unsafeReason(),
          approvedEnglish.safe() ? null : approvedEnglish.unsafeReason(),
          occurrence.ordinal(),
          null,
          blankToNull(occurrence.heading()),
          reason == null ? "unsafe approved input" : reason,
          null,
          null,
          null));
    }
    return new MigrationPage(
        raw.pageRef(),
        raw.sourcePath(),
        UNSAFE_PAGE,
        false,
        List.copyOf(occurrences),
        approvedRussian,
        approvedEnglish);
  }

  private static MigrationOccurrence toOccurrence(
      RawPage raw,
      ApprovedDocument ru,
      ApprovedDocument en,
      WorkingOccurrence item,
      Classification classification,
      String reason) {
    RawOccurrence occurrence = item.raw();
    String refId = referenceId(occurrence.ordinal());
    String targetRef = item.resolution().pageRef();
    PageReferenceMap.Reference reference = classification == EXACT
        ? new PageReferenceMap.Reference(
            targetRef,
            occurrence.authoredTarget(),
            occurrence.heading(),
            occurrence.label())
        : null;
    ApprovedSpan ruSpan = item.ruCandidates().size() == 1 ? item.ruCandidates().getFirst() : null;
    ApprovedSpan enSpan = item.enCandidates().size() == 1 ? item.enCandidates().getFirst() : null;
    return new MigrationOccurrence(
        raw.pageRef() + "/" + refId,
        classification,
        occurrence.source(),
        context(raw.markdown(), occurrence.start(), occurrence.end()),
        ruSpan == null ? null : context(ru.text(), ruSpan.start(), ruSpan.end()),
        enSpan == null ? null : context(en.text(), enSpan.start(), enSpan.end()),
        occurrence.ordinal(),
        targetRef,
        blankToNull(occurrence.heading()),
        reason,
        classification == EXACT ? refId : null,
        reference,
        enSpan == null ? null : new Span(enSpan.start(), enSpan.end()));
  }

  private static Classification classify(
      List<ApprovedSpan> ruCandidates,
      List<ApprovedSpan> enCandidates,
      boolean duplicate) {
    if (duplicate) {
      return AMBIGUOUS_TRANSLATION;
    }
    if (ruCandidates.size() == 1 && enCandidates.size() == 1) {
      return EXACT;
    }
    return AMBIGUOUS_TRANSLATION;
  }

  private static String reason(
      Classification classification,
      List<ApprovedSpan> ruCandidates,
      List<ApprovedSpan> enCandidates,
      boolean duplicate) {
    if (classification == EXACT) {
      return "unique RU/EN/target alignment";
    }
    if (duplicate) {
      return "duplicate raw target and label require confirmation";
    }
    if (enCandidates.size() > 1) {
      return "two monotonic EN spans remain";
    }
    if (ruCandidates.isEmpty() || enCandidates.isEmpty()) {
      return "legacy approved span does not match current target route";
    }
    return "legacy approved spans require confirmation";
  }

  private static boolean sourceContextDrift(String raw, String approvedRussian) {
    return !normalizeComparable(renderRawLabels(raw)).equals(normalizeComparable(renderApprovedLabels(approvedRussian)));
  }

  private static String renderRawLabels(String raw) {
    StringBuilder rendered = new StringBuilder(raw.length());
    int cursor = 0;
    for (RawOccurrence occurrence : parseRaw(raw)) {
      rendered.append(raw, cursor, occurrence.start());
      rendered.append(occurrence.label());
      cursor = occurrence.end();
    }
    rendered.append(raw.substring(cursor));
    return maskProtected(rendered.toString());
  }

  private static String renderApprovedLabels(String markdown) {
    StringBuilder rendered = new StringBuilder(markdown.length());
    int cursor = 0;
    Matcher matcher = MARKDOWN_LINK.matcher(markdown);
    List<MarkdownScanner.Span> spans = MarkdownScanner.protectedSpans(markdown);
    while (matcher.find()) {
      if (insideProtected(matcher.start(), matcher.end(), spans) || isEscaped(markdown, matcher.start())) {
        continue;
      }
      rendered.append(markdown, cursor, matcher.start());
      rendered.append(matcher.group("label"));
      cursor = matcher.end();
    }
    rendered.append(markdown.substring(cursor));
    return maskProtected(rendered.toString());
  }

  private static String maskProtected(String text) {
    StringBuilder result = new StringBuilder(text);
    for (MarkdownScanner.Span span : MarkdownScanner.protectedSpans(text)) {
      for (int index = span.start(); index < span.end(); index++) {
        char character = result.charAt(index);
        if (character != '\r' && character != '\n') {
          result.setCharAt(index, ' ');
        }
      }
    }
    return result.toString();
  }

  private static String normalizeComparable(String text) {
    return text.replaceAll("\\s+", " ").strip();
  }

  private static int uniqueAssignmentCount(List<WorkingOccurrence> occurrences, boolean russian) {
    int count = 0;
    for (WorkingOccurrence occurrence : occurrences) {
      List<ApprovedSpan> spans = russian ? occurrence.ruCandidates() : occurrence.enCandidates();
      if (spans.size() == 1) {
        count++;
      }
    }
    return count;
  }

  private static List<String> documentOrder(List<WorkingOccurrence> occurrences, boolean russian) {
    return occurrences.stream()
        .filter(occurrence -> (russian ? occurrence.ruCandidates() : occurrence.enCandidates()).size() == 1)
        .sorted(Comparator.comparingInt(occurrence ->
            (russian ? occurrence.ruCandidates() : occurrence.enCandidates()).getFirst().start()))
        .map(occurrence -> occurrence.resolution().pageRef())
        .toList();
  }

  private static int resolvedCount(List<WorkingOccurrence> occurrences) {
    int count = 0;
    for (WorkingOccurrence occurrence : occurrences) {
      if (occurrence.resolution().status() == VaultReferenceResolver.Status.RESOLVED) {
        count++;
      }
    }
    return count;
  }

  private static PageStatus pageStatus(List<MigrationOccurrence> occurrences) {
    if (occurrences.stream().anyMatch(occurrence -> occurrence.classification() == UNSAFE_INPUT)) {
      return UNSAFE_PAGE;
    }
    if (occurrences.stream().anyMatch(occurrence -> occurrence.classification() == ORDER_MISMATCH)) {
      return ORDER_MISMATCH_PAGE;
    }
    if (occurrences.stream().anyMatch(occurrence -> occurrence.classification() == UNRESOLVED_TARGET)) {
      return UNRESOLVED_PAGE;
    }
    if (occurrences.stream().allMatch(occurrence -> occurrence.classification() == EXACT)) {
      return EXACT_PAGE;
    }
    return CONFIRMED_NEEDED;
  }

  private static Map<String, Integer> duplicateSignatures(
      List<RawOccurrence> occurrences,
      VaultReferenceResolver resolver) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (RawOccurrence occurrence : occurrences) {
      VaultReferenceResolver.Resolution resolution = resolver.resolve("", occurrence.authoredTarget());
      if (resolution.status() == VaultReferenceResolver.Status.RESOLVED) {
        counts.merge(signature(resolution, occurrence), 1, Integer::sum);
      }
    }
    return counts;
  }

  private static String signature(
      VaultReferenceResolver.Resolution resolution,
      RawOccurrence occurrence) {
    return resolution.pageRef() + "|" + occurrence.label() + "|" + occurrence.heading();
  }

  private static List<String> routesFor(String currentPath) {
    String stem = stem(currentPath);
    String path = stripExtension(currentPath == null ? "" : currentPath.replace('\\', '/'))
        .toLowerCase(java.util.Locale.ROOT);
    return List.of(
        "/ru/" + stem + "/",
        "/en/" + stem + "/",
        "/ru/" + path + "/",
        "/en/" + path + "/");
  }

  private static String stem(String currentPath) {
    String value = currentPath == null ? "" : currentPath.replace('\\', '/');
    int slash = value.lastIndexOf('/');
    if (slash >= 0) {
      value = value.substring(slash + 1);
    }
    value = value.replaceFirst("\\.md$", "");
    return TIMESTAMP.matcher(value).replaceFirst("").toLowerCase(java.util.Locale.ROOT);
  }

  private static String stripExtension(String value) {
    return value.endsWith(".md") ? value.substring(0, value.length() - 3) : value;
  }

  private static List<ApprovedSpan> candidates(
      List<ApprovedSpan> spans,
      String label,
      List<String> routes,
      String language) {
    List<ApprovedSpan> matches = new ArrayList<>();
    String languagePrefix = "/" + language + "/";
    for (ApprovedSpan span : spans) {
      if (!span.label().equals(label)) {
        continue;
      }
      if (span.destination() == null) {
        matches.add(span);
        continue;
      }
      if (!span.destination().startsWith(languagePrefix)) {
        continue;
      }
      if (routes.contains(span.destination())) {
        matches.add(span);
      }
    }
    return List.copyOf(matches);
  }

  private static List<ApprovedSpan> parseApproved(String markdown, String language) {
    List<ApprovedSpan> spans = new ArrayList<>();
    List<MarkdownScanner.Span> protectedSpans = MarkdownScanner.protectedSpans(markdown);
    Matcher link = MARKDOWN_LINK.matcher(markdown);
    Set<String> linkedRanges = new LinkedHashSet<>();
    while (link.find()) {
      if (insideProtected(link.start(), link.end(), protectedSpans) || isEscaped(markdown, link.start())) {
        continue;
      }
      spans.add(new ApprovedSpan(
          link.start(),
          link.end(),
          link.group("label"),
          link.group("destination"),
          markdown.substring(link.start(), link.end())));
      linkedRanges.add(link.start() + ":" + link.end());
    }
    Matcher word = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_-]*").matcher(markdown);
    while (word.find()) {
      if (insideProtected(word.start(), word.end(), protectedSpans)) {
        continue;
      }
      if (coveredBy(linkedRanges, word.start(), word.end())) {
        continue;
      }
      String label = word.group().strip();
      if (!label.isEmpty()) {
        spans.add(new ApprovedSpan(word.start(), word.end(), label, null, label));
      }
    }
    return spans.stream()
        .sorted(Comparator.comparingInt(ApprovedSpan::start))
        .toList();
  }

  private static boolean coveredBy(Set<String> ranges, int start, int end) {
    for (String range : ranges) {
      int split = range.indexOf(':');
      int rangeStart = Integer.parseInt(range.substring(0, split));
      int rangeEnd = Integer.parseInt(range.substring(split + 1));
      if (start >= rangeStart && end <= rangeEnd) {
        return true;
      }
    }
    return false;
  }

  private static List<RawOccurrence> parseRaw(String body) {
    List<RawOccurrence> occurrences = new ArrayList<>();
    List<MarkdownScanner.Span> spans = MarkdownScanner.protectedSpans(body);
    Matcher matcher = WIKILINK.matcher(body);
    int ordinal = 1;
    while (matcher.find()) {
      if (isEscaped(body, matcher.start()) || insideProtected(matcher.start(), matcher.end(), spans)) {
        continue;
      }
      if (!matcher.group(1).isEmpty()) {
        continue;
      }
      String target = matcher.group(2).strip();
      String heading = matcher.group(3) == null ? "" : matcher.group(3);
      String label = matcher.group(4) == null ? defaultLabel(target) : matcher.group(4).strip();
      occurrences.add(new RawOccurrence(
          matcher.start(),
          matcher.end(),
          target,
          heading,
          label,
          matcher.group(),
          ordinal++));
    }
    return List.copyOf(occurrences);
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

  private static boolean isEscaped(String source, int index) {
    int cursor = index - 1;
    int escapes = 0;
    while (cursor >= 0 && source.charAt(cursor) == '\\') {
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
      // Keep the authored target if it is not path-like.
    }
    return TIMESTAMP.matcher(fallback.replaceFirst("\\.md$", "")).replaceFirst("").strip();
  }

  private static String context(String text, int start, int end) {
    int before = Math.max(0, start - 40);
    int after = Math.min(text.length(), end + 40);
    return text.substring(before, after).replaceAll("\\s+", " ").strip();
  }

  private static String referenceId(int ordinal) {
    return "ref-%04d".formatted(ordinal);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private record RawOccurrence(
      int start,
      int end,
      String target,
      String heading,
      String label,
      String source,
      int ordinal) {
    String authoredTarget() {
      return target + (heading == null ? "" : heading);
    }
  }

  private record ApprovedSpan(
      int start,
      int end,
      String label,
      String destination,
      String source) {
  }

  private record WorkingOccurrence(
      RawOccurrence raw,
      VaultReferenceResolver.Resolution resolution,
      List<ApprovedSpan> ruCandidates,
      List<ApprovedSpan> enCandidates,
      Classification classification,
      String reason) {
  }

  public record RawPage(String pageRef, String sourcePath, String markdown) { }

  public record ApprovedDocument(String path, boolean safe, String text, String unsafeReason) {
    public static ApprovedDocument valid(String path, byte[] bytes) {
      try {
        String text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
        return new ApprovedDocument(path, true, text, null);
      } catch (CharacterCodingException error) {
        return unsafe(path, "invalid UTF-8");
      }
    }

    public static ApprovedDocument unsafe(String path, String reason) {
      return new ApprovedDocument(path, false, "", reason);
    }
  }

  public record MigrationPage(
      String pageRef,
      String sourcePath,
      PageStatus status,
      boolean automatic,
      List<MigrationOccurrence> occurrences,
      ApprovedDocument approvedRussian,
      ApprovedDocument approvedEnglish) {
    public MigrationPage {
      occurrences = List.copyOf(occurrences);
    }
  }

  public record MigrationOccurrence(
      String occurrenceKey,
      Classification classification,
      String rawWikilink,
      String sourceContext,
      String ruContext,
      String proposedEnContext,
      int sourceOrdinal,
      String targetRef,
      String heading,
      String reason,
      String proposedReferenceId,
      PageReferenceMap.Reference proposedReference,
      Span proposedEnSpan) {
  }

  public record Span(int start, int end) { }

  public enum Classification {
    EXACT("exact"),
    UNRESOLVED_TARGET("unresolved-target"),
    AMBIGUOUS_TRANSLATION("ambiguous-translation"),
    ORDER_MISMATCH("order-mismatch"),
    UNSAFE_INPUT("unsafe-input");

    private final String json;

    Classification(String json) {
      this.json = json;
    }

    public String json() {
      return json;
    }
  }

  public enum PageStatus {
    EXACT_PAGE("exact"),
    CONFIRMED_NEEDED("confirmed-needed"),
    UNRESOLVED_PAGE("unresolved"),
    ORDER_MISMATCH_PAGE("order-mismatch"),
    UNSAFE_PAGE("unsafe");

    private final String json;

    PageStatus(String json) {
      this.json = json;
    }

    public String json() {
      return json;
    }
  }
}
