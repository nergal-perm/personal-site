package dev.eugene.astroexport.migration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

public final class SemanticSchemaState {
  private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

  private SemanticSchemaState() { }

  public static Path activationMarker(Path reviewRoot) {
    return reviewRoot.resolve(".semantic-links/schema-v1.active.json");
  }

  public static Path migrationJournal(Path reviewRoot) {
    return reviewRoot.resolve(".semantic-links/migration-v1.journal.json");
  }

  public static Mode mode(Path reviewRoot) {
    Path marker = activationMarker(reviewRoot);
    boolean hasMarker = Files.exists(marker);
    boolean hasJournal = Files.exists(migrationJournal(reviewRoot));
    if (!hasMarker && !hasJournal) {
      return Mode.LEGACY;
    }
    if (!hasMarker) {
      return Mode.MIGRATION_INCOMPLETE;
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = JSON.readValue(Files.readAllBytes(marker), Map.class);
      if (!validMarker(payload)) {
        return Mode.MIGRATION_INCOMPLETE;
      }
      if (hasJournal && !journalMatches(payload, migrationJournal(reviewRoot))) {
        return Mode.MIGRATION_INCOMPLETE;
      }
      return Mode.SEMANTIC;
    } catch (Exception error) {
      return Mode.MIGRATION_INCOMPLETE;
    }
  }

  private static boolean validMarker(Map<String, Object> payload) {
    return payload.size() == 4
        && Integer.valueOf(1).equals(payload.get("schemaVersion"))
        && validSha(payload.get("inventorySha256"))
        && validSha(payload.get("catalogSha256"))
        && validInstant(payload.get("activatedAt"));
  }

  private static boolean journalMatches(Map<String, Object> marker, Path journal) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = JSON.readValue(Files.readAllBytes(journal), Map.class);
      return "complete".equals(payload.get("state"))
          && Integer.valueOf(1).equals(payload.get("schemaVersion"))
          && marker.get("inventorySha256").equals(payload.get("inventorySha256"))
          && marker.get("catalogSha256").equals(payload.get("catalogSha256"))
          && validCatalogEvidence(payload)
          && validJournalRecoveryRoot(payload.get("recoveryRoot"))
          && validJournalPages(payload.get("pages"));
    } catch (Exception error) {
      return false;
    }
  }

  private static boolean validJournalRecoveryRoot(Object value) {
    return value instanceof String text && !text.isBlank();
  }

  private static boolean validCatalogEvidence(Map<String, Object> payload) {
    return "complete".equals(payload.get("catalogState"))
        && nonBlank(payload.get("catalogPublished"))
        && nonBlank(payload.get("catalogStaged"))
        && nonBlank(payload.get("catalogDisplaced"));
  }

  private static boolean validJournalPages(Object value) {
    if (!(value instanceof java.util.List<?> pages) || pages.isEmpty()) {
      return false;
    }
    for (Object page : pages) {
      if (!(page instanceof Map<?, ?> payload)
          || !nonBlank(payload.get("collection"))
          || !nonBlank(payload.get("publicId"))
          || !nonBlank(payload.get("pageRef"))
          || !nonBlank(payload.get("sourcePath"))
          || (!"complete".equals(payload.get("state"))
              && !"cleanup-pending".equals(payload.get("state")))
          || !validSha(payload.get("stagedSha256"))
          || !nonBlank(payload.get("published"))
          || !nonBlank(payload.get("staged"))
          || !nonBlank(payload.get("displaced"))) {
        return false;
      }
    }
    return true;
  }

  private static boolean nonBlank(Object value) {
    return value instanceof String text && !text.isBlank();
  }

  private static boolean validSha(Object value) {
    return value instanceof String text && SHA256.matcher(text).matches();
  }

  private static boolean validInstant(Object value) {
    if (!(value instanceof String text)) {
      return false;
    }
    try {
      Instant.parse(text);
      return true;
    } catch (RuntimeException error) {
      return false;
    }
  }

  public enum Mode {
    LEGACY,
    SEMANTIC,
    MIGRATION_INCOMPLETE
  }
}
