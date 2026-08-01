package dev.eugene.astroexport.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.markdown.MarkdownScanner;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes deterministic, human-reviewable page-corrected decision drafts. */
public final class SemanticDecisionDraftWriter {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[(?<label>[^\\]\\n]*?)\\]\\((?<destination>[^\\r\\n)]*?)\\)");

  public void write(
      Path decisionsPath,
      ReferenceMigrationInventory.Inventory inventory,
      Path reviewRoot) {
    Path review = Objects.requireNonNull(reviewRoot, "reviewRoot").toAbsolutePath().normalize();
    Path destination = SemanticOutputSafety.preflight(decisionsPath, review, "draft path");
    Path base = destination.getParent();
    if (base == null) {
      throw new IllegalArgumentException("draft path must have a parent");
    }
    Path outputRoot = base.resolve(destination.getFileName() + ".files").normalize();
    SemanticOutputSafety.preflight(outputRoot, review, "draft output tree");
    try {
      LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
      payload.put("schemaVersion", 1);
      payload.put("draftOnly", true);
      payload.put("draftStatus", "needs-human-conversion");
      payload.put("inventorySha256", inventory.inventorySha256());
      payload.put("draftFormat", "page-corrected-v1");
      List<Map<String, Object>> pages = new ArrayList<>();
      LinkedHashMap<String, Object> decisions = new LinkedHashMap<>();
      List<OutputFile> outputs = new ArrayList<>();
      int pageNumber = 1;
      for (ReferenceMigrationAligner.MigrationPage page : inventory.pages()) {
        List<Map<String, Object>> occurrences = page.occurrences().stream()
            .map(SemanticDecisionDraftWriter::occurrencePayload)
            .toList();
        LinkedHashMap<String, Object> pagePayload = new LinkedHashMap<>();
        pagePayload.put("pageRef", page.pageRef());
        pagePayload.put("sourcePath", page.sourcePath());
        pagePayload.put("status", page.status().json());
        pagePayload.put("automatic", page.automatic());
        pagePayload.put("occurrences", occurrences);
        if (executablePage(page)) {
          String folder = "pages/%03d-%s".formatted(pageNumber++, safeName(page.pageRef()));
          Path russianPath = outputRoot.resolve(folder).resolve("corrected-ru.md");
          Path englishPath = outputRoot.resolve(folder).resolve("corrected-en.md");
          Correction russian = corrected(page.approvedRussian().text(), page.occurrences(), true);
          Correction english = corrected(page.approvedEnglish().text(), page.occurrences(), false);
          if (russian.changed() || english.changed()) {
            outputs.add(new OutputFile(russianPath, russian.bytes(), "corrected Russian draft"));
            outputs.add(new OutputFile(englishPath, english.bytes(), "corrected English draft"));
          }
          if (russian.complete() && english.complete()) {
            LinkedHashMap<String, Object> decision = new LinkedHashMap<>();
            decision.put("decision", "approve-corrected-page");
            decision.put("correctedRussianPath", base.relativize(russianPath).toString());
            decision.put("correctedEnglishPath", base.relativize(englishPath).toString());
            decision.put("approvedRussianSha256", hash(page.approvedRussian().text()));
            decision.put("approvedEnglishSha256", hash(page.approvedEnglish().text()));
            decision.put("correctedRussianSha256", hash(russian.bytes()));
            decision.put("correctedEnglishSha256", hash(english.bytes()));
            decisions.put(page.pageRef() + "/page", decision);
            pagePayload.put("correctedRussianPath", base.relativize(russianPath).toString());
            pagePayload.put("correctedEnglishPath", base.relativize(englishPath).toString());
          } else if (russian.changed() || english.changed()) {
            pagePayload.put("draftBinding", "occurrence-specific-span-verification-required");
          } else {
            pagePayload.put("draftBinding", "context-only-occurrence-span-unverified");
          }
        }
        pages.add(pagePayload);
      }
      payload.put("pages", pages);
      payload.put("decisions", decisions);
      outputs.add(new OutputFile(destination, JSON.writeValueAsBytes(payload), "decision draft"));
      for (OutputFile output : outputs) {
        SemanticOutputSafety.preflight(output.path(), review, output.kind());
        SemanticOutputSafety.rejectConflict(output.path(), output.bytes(), output.kind());
      }
      for (OutputFile output : outputs) {
        writeFile(output.path(), output.bytes(), review, output.kind());
      }
    } catch (IOException error) {
      throw new UncheckedIOException("cannot write semantic decision draft", error);
    }
  }

  private static boolean executablePage(ReferenceMigrationAligner.MigrationPage page) {
    return page.status() == ReferenceMigrationAligner.PageStatus.CONFIRMED_NEEDED
        && page.approvedRussian().safe()
        && page.approvedEnglish().safe()
        && page.occurrences().stream().allMatch(occurrence ->
            occurrence.targetRef() != null && !occurrence.targetRef().isBlank());
  }

  private static Map<String, Object> occurrencePayload(ReferenceMigrationAligner.MigrationOccurrence occurrence) {
    LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("occurrenceKey", occurrence.occurrenceKey());
    payload.put("rawWikilink", occurrence.rawWikilink());
    payload.put("targetRef", occurrence.targetRef());
    payload.put("heading", occurrence.heading());
    payload.put("reason", occurrence.reason());
    payload.put("sourceContext", occurrence.sourceContext());
    payload.put("ruContext", occurrence.ruContext());
    payload.put("proposedEnContext", occurrence.proposedEnContext());
    payload.put("proposedRuDestination", occurrence.proposedRuDestination());
    payload.put("proposedRuSpan", span(occurrence.proposedRuSpan()));
    payload.put("proposedEnSpan", span(occurrence.proposedEnSpan()));
    payload.put("classification", occurrence.classification().json());
    return payload;
  }

  private static Map<String, Integer> span(ReferenceMigrationAligner.Span value) {
    if (value == null) return null;
    return Map.of("start", value.start(), "end", value.end());
  }

  private static Correction corrected(
      String approved,
      List<ReferenceMigrationAligner.MigrationOccurrence> occurrences,
      boolean russian) {
    Map<Integer, Replacement> replacements = new HashMap<>();
    Set<String> occurrenceSpans = new HashSet<>();
    boolean complete = true;
    for (ReferenceMigrationAligner.MigrationOccurrence occurrence : occurrences) {
      Replacement replacement = verifiedReplacement(approved, occurrence, russian);
      if (replacement == null) {
        complete = false;
        continue;
      }
      String spanKey = replacement.start() + ":" + replacement.end();
      if (!occurrenceSpans.add(spanKey)) {
        return new Correction(approved.getBytes(StandardCharsets.UTF_8), false, false);
      }
      if (replacements.put(replacement.start(), replacement) != null) {
        return new Correction(approved.getBytes(StandardCharsets.UTF_8), false, false);
      }
    }
    if (replacements.isEmpty()) {
      return new Correction(approved.getBytes(StandardCharsets.UTF_8), complete, false);
    }
    List<Replacement> ordered = replacements.values().stream()
        .sorted(java.util.Comparator.comparingInt(Replacement::start)).toList();
    for (int index = 1; index < ordered.size(); index++) {
      if (ordered.get(index).start() < ordered.get(index - 1).end()) {
        return new Correction(approved.getBytes(StandardCharsets.UTF_8), false, false);
      }
    }
    StringBuilder result = new StringBuilder();
    int cursor = 0;
    for (Replacement replacement : ordered) {
      result.append(approved, cursor, replacement.start());
      result.append('[').append(replacement.label()).append("](ref:")
          .append(replacement.referenceId()).append(')');
      cursor = replacement.end();
    }
    result.append(approved, cursor, approved.length());
    String corrected = result.toString();
    return new Correction(corrected.getBytes(StandardCharsets.UTF_8), complete, !corrected.equals(approved));
  }

  private static Replacement verifiedReplacement(
      String approved,
      ReferenceMigrationAligner.MigrationOccurrence occurrence,
      boolean russian) {
    ReferenceMigrationAligner.Span span = russian
        ? occurrence.proposedRuSpan()
        : occurrence.proposedEnSpan();
    String destination = russian
        ? occurrence.proposedRuDestination()
        : occurrence.proposedEnDestination();
    String expectedContext = russian ? occurrence.ruContext() : occurrence.proposedEnContext();
    if (span == null || destination == null || expectedContext == null) {
      return null;
    }
    if (span.start() < 0 || span.end() <= span.start() || span.end() > approved.length()) {
      return null;
    }
    if ((span.start() > 0 && approved.charAt(span.start() - 1) == '!')
        || isEscaped(approved, span.start())) {
      return null;
    }
    if (insideProtected(span.start(), span.end(), MarkdownScanner.protectedSpans(approved))) {
      return null;
    }
    Matcher matcher = MARKDOWN_LINK.matcher(approved);
    matcher.region(span.start(), span.end());
    if (!matcher.lookingAt() || matcher.end() != span.end()) {
      return null;
    }
    if (!matcher.group("label").equals(occurrenceLabel(occurrence))
        || !matcher.group("destination").equals(destination)
        || !context(approved, span.start(), span.end()).equals(expectedContext)) {
      return null;
    }
    return new Replacement(span.start(), span.end(), matcher.group("label"), referenceId(occurrence));
  }

  private static String occurrenceLabel(ReferenceMigrationAligner.MigrationOccurrence occurrence) {
    if (occurrence.proposedReference() != null && occurrence.proposedReference().label() != null) {
      return occurrence.proposedReference().label();
    }
    String raw = occurrence.rawWikilink();
    int pipe = raw.indexOf('|');
    int end = raw.lastIndexOf("]]");
    if (pipe >= 0 && end > pipe) {
      return raw.substring(pipe + 1, end).strip();
    }
    int start = raw.indexOf("[[") + 2;
    return end > start ? raw.substring(start, end).strip() : "";
  }

  private static String context(String text, int start, int end) {
    int before = Math.max(0, start - 40);
    int after = Math.min(text.length(), end + 40);
    return text.substring(before, after).replaceAll("\\s+", " ").strip();
  }

  private static boolean insideProtected(int start, int end, List<MarkdownScanner.Span> spans) {
    for (MarkdownScanner.Span span : spans) {
      if (span.end() <= start) continue;
      if (span.start() >= end) return false;
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

  private static String referenceId(ReferenceMigrationAligner.MigrationOccurrence occurrence) {
    return occurrence.proposedReferenceId() == null || occurrence.proposedReferenceId().isBlank()
        ? "ref-%04d".formatted(occurrence.sourceOrdinal())
        : occurrence.proposedReferenceId();
  }

  private static String hash(String text) {
    return hash(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String hash(byte[] bytes) {
    return PageReferenceMapCodec.sha256(bytes);
  }

  private static String safeName(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static void writeFile(Path path, byte[] bytes, Path review, String kind) throws IOException {
    Path parent = path.getParent();
    if (parent == null) {
      throw new IllegalArgumentException(kind + " must have a parent");
    }
    SemanticOutputSafety.createDirectories(parent, review, kind);
    SemanticOutputSafety.preflight(path, review, kind);
    SemanticOutputSafety.rejectConflict(path, bytes, kind);
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Path temporary = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".tmp");
    try {
      Files.write(temporary, bytes);
      SemanticOutputSafety.preflight(temporary, review, kind + " temporary");
      SemanticOutputSafety.preflight(path, review, kind);
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.FileAlreadyExistsException error) {
      throw new IllegalArgumentException(kind + " already exists", error);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private record OutputFile(Path path, byte[] bytes, String kind) {
    private OutputFile {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  private record Replacement(int start, int end, String label, String referenceId) { }

  private record Correction(byte[] bytes, boolean complete, boolean changed) {
    private Correction {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
