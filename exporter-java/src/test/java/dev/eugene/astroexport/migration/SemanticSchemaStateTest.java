package dev.eugene.astroexport.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SemanticSchemaStateTest {
  @TempDir
  Path temp;

  @Test
  void absentMarkerAndAbsentJournalRemainLegacy() {
    assertEquals(SemanticSchemaState.Mode.LEGACY, SemanticSchemaState.mode(temp));
  }

  @Test
  void validActivationMarkerEnablesSemanticMode() throws Exception {
    Path marker = temp.resolve(".semantic-links/schema-v1.active.json");
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, """
        {
          "schemaVersion": 1,
          "inventorySha256": "%s",
          "catalogSha256": "%s",
          "activatedAt": "2026-07-30T00:00:00Z"
        }
        """.formatted("a".repeat(64), "b".repeat(64)));

    assertEquals(SemanticSchemaState.Mode.SEMANTIC, SemanticSchemaState.mode(temp));
  }

  @Test
  void migrationJournalWithoutCompleteMarkerBlocksAsIncomplete() throws Exception {
    Path journal = temp.resolve(".semantic-links/migration-v1.journal.json");
    Files.createDirectories(journal.getParent());
    Files.writeString(journal, "{\"state\":\"installed\"}");

    assertEquals(SemanticSchemaState.Mode.MIGRATION_INCOMPLETE,
        SemanticSchemaState.mode(temp));
  }

  @Test
  void validMarkerWithUnmatchedJournalBlocksAsIncomplete() throws Exception {
    writeMarker("a".repeat(64), "b".repeat(64));
    Path journal = temp.resolve(".semantic-links/migration-v1.journal.json");
    Files.writeString(journal, """
        {
          "state": "complete",
          "inventorySha256": "%s",
          "catalogSha256": "%s"
        }
        """.formatted("a".repeat(64), "c".repeat(64)));

    assertEquals(SemanticSchemaState.Mode.MIGRATION_INCOMPLETE,
        SemanticSchemaState.mode(temp));
  }

  @Test
  void validMarkerWithMatchingCompleteJournalEnablesSemanticMode() throws Exception {
    writeMarker("a".repeat(64), "b".repeat(64));
    Path journal = temp.resolve(".semantic-links/migration-v1.journal.json");
    Files.writeString(journal, """
        {
          "schemaVersion": 1,
          "state": "complete",
          "inventorySha256": "%s",
          "catalogSha256": "%s",
          "catalogState": "complete",
          "catalogPublished": ".semantic-links/catalog-v1.json",
          "catalogStaged": ".semantic-links/staging-v1/catalog-v1.json",
          "catalogDisplaced": ".semantic-links/recovery-v1/catalog-v1.json",
          "pages": [
            {"collection":"blog","publicId":"page","pageRef":"vault-ref-page","sourcePath":"page.md","state":"complete","stagedSha256":"%s","published":"blog/page/published","staged":"staging/page","displaced":"recovery/page"}
          ],
          "recoveryRoot": ".semantic-links/recovery"
        }
        """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64)));

    assertEquals(SemanticSchemaState.Mode.SEMANTIC, SemanticSchemaState.mode(temp));
  }

  @Test
  void completeJournalMissingPageEvidenceBlocksAsIncomplete() throws Exception {
    writeMarker("a".repeat(64), "b".repeat(64));
    Path journal = temp.resolve(".semantic-links/migration-v1.journal.json");
    Files.writeString(journal, """
        {
          "state": "complete",
          "inventorySha256": "%s",
          "catalogSha256": "%s",
          "pages": [],
          "recoveryRoot": ".semantic-links/recovery"
        }
        """.formatted("a".repeat(64), "b".repeat(64)));

    assertEquals(SemanticSchemaState.Mode.MIGRATION_INCOMPLETE,
        SemanticSchemaState.mode(temp));
  }

  @Test
  void malformedMarkerBlocksAsIncompleteInsteadOfGuessing() throws Exception {
    Path marker = temp.resolve(".semantic-links/schema-v1.active.json");
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, "{\"schemaVersion\":1,\"inventorySha256\":\"short\"}");

    assertEquals(SemanticSchemaState.Mode.MIGRATION_INCOMPLETE,
        SemanticSchemaState.mode(temp));
  }

  private void writeMarker(String inventorySha256, String catalogSha256) throws Exception {
    Path marker = temp.resolve(".semantic-links/schema-v1.active.json");
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, """
        {
          "schemaVersion": 1,
          "inventorySha256": "%s",
          "catalogSha256": "%s",
          "activatedAt": "2026-07-30T00:00:00Z"
        }
        """.formatted(inventorySha256, catalogSha256));
  }
}
