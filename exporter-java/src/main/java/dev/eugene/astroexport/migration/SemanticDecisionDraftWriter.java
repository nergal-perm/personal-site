package dev.eugene.astroexport.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    Path destination = decisionsPath.toAbsolutePath().normalize();
    Path review = Objects.requireNonNull(reviewRoot, "reviewRoot").toAbsolutePath().normalize();
    if (destination.startsWith(review)) {
      throw new IllegalArgumentException("draft path must be outside the review root");
    }
    rejectRealPathInsideReview(destination, review);
    Path base = destination.getParent();
    if (base == null) {
      throw new IllegalArgumentException("draft path must have a parent");
    }
    try {
      Files.createDirectories(base);
      LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
      payload.put("schemaVersion", 1);
      payload.put("draftOnly", true);
      payload.put("draftStatus", "needs-human-conversion");
      payload.put("inventorySha256", inventory.inventorySha256());
      payload.put("draftFormat", "page-corrected-v1");
      List<Map<String, Object>> pages = new ArrayList<>();
      LinkedHashMap<String, Object> decisions = new LinkedHashMap<>();
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
          Path russianPath = base.resolve(folder).resolve("corrected-ru.md");
          Path englishPath = base.resolve(folder).resolve("corrected-en.md");
          byte[] russian = corrected(page.approvedRussian().text(), page.occurrences());
          byte[] english = corrected(page.approvedEnglish().text(), page.occurrences());
          writeFile(russianPath, russian);
          writeFile(englishPath, english);
          LinkedHashMap<String, Object> decision = new LinkedHashMap<>();
          decision.put("decision", "approve-corrected-page");
          decision.put("correctedRussianPath", base.relativize(russianPath).toString());
          decision.put("correctedEnglishPath", base.relativize(englishPath).toString());
          decision.put("approvedRussianSha256", hash(page.approvedRussian().text()));
          decision.put("approvedEnglishSha256", hash(page.approvedEnglish().text()));
          decision.put("correctedRussianSha256", hash(russian));
          decision.put("correctedEnglishSha256", hash(english));
          decisions.put(page.pageRef() + "/page", decision);
          pagePayload.put("correctedRussianPath", base.relativize(russianPath).toString());
          pagePayload.put("correctedEnglishPath", base.relativize(englishPath).toString());
        }
        pages.add(pagePayload);
      }
      payload.put("pages", pages);
      payload.put("decisions", decisions);
      writeFile(destination, JSON.writeValueAsBytes(payload));
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

  private static void rejectRealPathInsideReview(Path destination, Path review) {
    try {
      Path reviewReal = review.toRealPath();
      Path existing = destination;
      List<Path> unresolvedSuffix = new ArrayList<>();
      while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
        Path name = existing.getFileName();
        if (name == null || existing.getParent() == null) {
          throw new IllegalArgumentException("draft path cannot be resolved safely");
        }
        unresolvedSuffix.add(name);
        existing = existing.getParent();
      }
      Path candidate = existing.toRealPath();
      for (int index = unresolvedSuffix.size() - 1; index >= 0; index--) {
        candidate = candidate.resolve(unresolvedSuffix.get(index));
      }
      if (candidate.startsWith(reviewReal)) {
        throw new IllegalArgumentException("draft path resolves inside the review root");
      }
    } catch (IOException error) {
      throw new UncheckedIOException("cannot verify draft path safely", error);
    }
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
    payload.put("proposedEnSpan", span(occurrence.proposedEnSpan()));
    payload.put("classification", occurrence.classification().json());
    return payload;
  }

  private static Map<String, Integer> span(ReferenceMigrationAligner.Span value) {
    if (value == null) return null;
    return Map.of("start", value.start(), "end", value.end());
  }

  private static byte[] corrected(String approved, List<ReferenceMigrationAligner.MigrationOccurrence> occurrences) {
    Matcher matcher = MARKDOWN_LINK.matcher(approved);
    StringBuilder result = new StringBuilder();
    int cursor = 0;
    int occurrenceIndex = 0;
    while (matcher.find() && occurrenceIndex < occurrences.size()) {
      ReferenceMigrationAligner.MigrationOccurrence occurrence = occurrences.get(occurrenceIndex++);
      result.append(approved, cursor, matcher.start());
      result.append('[').append(matcher.group("label")).append("](ref:")
          .append(referenceId(occurrence)).append(')');
      cursor = matcher.end();
    }
    result.append(approved, cursor, approved.length());
    while (occurrenceIndex < occurrences.size()) {
      ReferenceMigrationAligner.MigrationOccurrence occurrence = occurrences.get(occurrenceIndex++);
      result.append("\n[review ").append(occurrence.rawWikilink()).append("](ref:")
          .append(referenceId(occurrence)).append(')');
    }
    return result.toString().getBytes(StandardCharsets.UTF_8);
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

  private static void writeFile(Path path, byte[] bytes) throws IOException {
    Files.createDirectories(path.getParent());
    Path temporary = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".tmp");
    try {
      Files.write(temporary, bytes);
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
