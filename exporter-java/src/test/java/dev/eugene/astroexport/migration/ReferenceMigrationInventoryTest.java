package dev.eugene.astroexport.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReferenceMigrationInventoryTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir
  Path temp;

  @Test
  void inspectWritesDeterministicInventoryAndOnlyMutatesReportPath() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    Path report = temp.resolve("inventory.json");
    writeNote(vault, "page.md", "See [[Target|target]].");
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "See [target](/ru/target/).",
        "See [target](/en/target/).");
    Map<String, ByteBuffer> beforeVault = treeSnapshot(vault);
    Map<String, ByteBuffer> beforeReview = treeSnapshot(review);

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, report);
    byte[] first = Files.readAllBytes(report);
    ReferenceMigrationInventory.Inventory second =
        new ReferenceMigrationInventory().inspect(vault, review, report);
    byte[] secondBytes = Files.readAllBytes(report);

    assertEquals(inventory.inventorySha256(), second.inventorySha256());
    assertEquals(ByteBuffer.wrap(first), ByteBuffer.wrap(secondBytes));
    Map<String, Object> payload = JSON.readValue(first, new TypeReference<>() { });
    assertEquals(1, payload.get("schemaVersion"));
    assertEquals(inventory.inventorySha256(), payload.get("inventorySha256"));
    assertEquals(PageReferenceMapCodec.sha256(canonicalWithoutHash(payload)), inventory.inventorySha256());
    assertEquals(List.of(pagePayload(
            "vault-ref-page",
            "page.md",
            "exact",
            true,
            occurrencePayload(
                "vault-ref-page/ref-0001",
                "exact",
                "[[Target|target]]",
                "See [[Target|target]].",
                "See [target](/ru/target/).",
                "See [target](/en/target/).",
                1,
                "vault-ref-target",
                null,
                "unique RU/EN/target alignment",
                Map.of(
                    "id", "ref-0001",
                    "targetRef", "vault-ref-target",
                    "authoredTarget", "Target",
                    "heading", "",
                    "label", "target")))),
        payload.get("pages"));
    assertEquals(Map.of(
        "exact", 1,
        "confirmedNeeded", 0,
        "unresolved", 0,
        "orderMismatch", 0,
        "unsafe", 0,
        "occurrences", 1),
        payload.get("summary"));
    assertEquals(beforeVault, treeSnapshot(vault));
    assertEquals(beforeReview, treeSnapshot(review));
  }

  @Test
  void inspectReportsSymlinkHardLinkAndInvalidUtf8AsUnsafeWithoutMutatingReview() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    writeNote(vault, "page.md", "[[Target|target]]");
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    Path published = review.resolve("blog/page/published");
    Files.createDirectories(published);
    Files.createSymbolicLink(published.resolve("ru.md"), vault.resolve("page.md"));
    Files.write(published.resolve("en.md"), new byte[] {(byte) 0xc3, 0x28});
    Files.writeString(published.resolve("references.json"), """
        {"schemaVersion":1,"pageRef":"vault-ref-page","sourcePath":"page.md","ruSha256":"x","enSha256":"y","order":[],"references":{}}
        """);
    Path hard = temp.resolve("hard.md");
    Files.writeString(hard, "hard");
    Files.createLink(review.resolve("blog/page/hard.md"), hard);
    Map<String, ByteBuffer> beforeReview = treeSnapshot(review);

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("report.json"));

    assertEquals("unsafe", inventory.pages().getFirst().status().json());
    assertEquals("unsafe-input", inventory.pages().getFirst().occurrences().getFirst().classification().json());
    assertTrue(inventory.pages().getFirst().occurrences().getFirst().reason().contains("unsafe approved snapshot"));
    assertEquals(beforeReview, treeSnapshot(review));
  }

  @Test
  void decisionsRejectStaleUnknownEscapingHashMismatchAndUnsupportedInputs() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    writeNote(vault, "page.md", "[[Target|target]]");
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "[target](/ru/target/)",
        "[target](/en/target/) [target](/en/target/)");
    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("inventory.json"));
    Path decisions = temp.resolve("decisions.json");

    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"stale","decisions":{}}
        """);
    assertDecisionRejected(inventory, decisions, "stale-inventory");

    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"missing/ref-0001":{"decision":"confirm","enSpan":{"start":0,"end":1}}}}
        """.formatted(inventory.inventorySha256()));
    assertDecisionRejected(inventory, decisions, "unknown-decision");

    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"vault-ref-page/ref-0001":{"decision":"confirm","enSpan":{"start":0,"end":6}}}}
        """.formatted(inventory.inventorySha256()));
    assertDecisionRejected(inventory, decisions, "hash-mismatch");

    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"vault-ref-page/ref-0001":{"decision":"delete"}}}
        """.formatted(inventory.inventorySha256()));
    assertDecisionRejected(inventory, decisions, "unsupported-decision");

    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"vault-ref-page/order":{"decision":"approve-corrected-order","correctedEnglishPath":"../escape.md","correctedEnglishSha256":"abc"}}}
        """.formatted(inventory.inventorySha256()));
    assertDecisionRejected(inventory, decisions, "escaping-corrected-path");
  }

  @Test
  void correctedEnglishDecisionRequiresCompleteReviewWithRuOrder() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    writeNote(vault, "page.md", "[[B|one]] [[C|two]]");
    writeNote(vault, "b.md", "B.");
    writeNote(vault, "c.md", "C.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-b", "b.md", "vault-ref-c", "c.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "[one](/ru/b/) [two](/ru/c/)",
        "[two](/en/c/) [one](/en/b/)");
    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("inventory.json"));
    Path corrected = temp.resolve("corrected/page-en.md");
    Files.createDirectories(corrected.getParent());
    Files.writeString(corrected, "[one](/en/b/) [two](/en/c/)", StandardCharsets.UTF_8);
    Path decisions = temp.resolve("decisions.json");
    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"vault-ref-page/order":{"decision":"approve-corrected-order","correctedEnglishPath":"corrected/page-en.md","correctedEnglishSha256":"%s"}}}
        """.formatted(inventory.inventorySha256(), PageReferenceMapCodec.sha256(Files.readAllBytes(corrected))));

    ReferenceMigrationInventory.DecisionSet decisionSet =
        new ReferenceMigrationInventory().validateDecisions(inventory, decisions);

    assertEquals(List.of("vault-ref-page/order"), decisionSet.keys());
  }

  private static void assertDecisionRejected(
      ReferenceMigrationInventory.Inventory inventory,
      Path decisions,
      String code) {
    ReferenceMigrationInventory.DecisionValidationException error = assertThrows(
        ReferenceMigrationInventory.DecisionValidationException.class,
        () -> new ReferenceMigrationInventory().validateDecisions(inventory, decisions));
    assertEquals(code, error.code());
  }

  private static byte[] canonicalWithoutHash(Map<String, Object> payload) throws Exception {
    Map<String, Object> copy = new java.util.LinkedHashMap<>(payload);
    copy.remove("inventorySha256");
    return JSON.writeValueAsBytes(copy);
  }

  private static Map<String, Object> pagePayload(
      String pageRef,
      String sourcePath,
      String status,
      boolean automatic,
      Map<String, Object> occurrence) {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("pageRef", pageRef);
    payload.put("sourcePath", sourcePath);
    payload.put("status", status);
    payload.put("automatic", automatic);
    payload.put("occurrences", List.of(occurrence));
    return payload;
  }

  private static Map<String, Object> occurrencePayload(
      String occurrenceKey,
      String classification,
      String rawWikilink,
      String sourceContext,
      String ruContext,
      String proposedEnContext,
      int sourceOrdinal,
      String targetRef,
      String heading,
      String reason,
      Map<String, Object> proposedReference) {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("occurrenceKey", occurrenceKey);
    payload.put("classification", classification);
    payload.put("rawWikilink", rawWikilink);
    payload.put("sourceContext", sourceContext);
    payload.put("ruContext", ruContext);
    payload.put("proposedEnContext", proposedEnContext);
    payload.put("sourceOrdinal", sourceOrdinal);
    payload.put("targetRef", targetRef);
    payload.put("heading", heading);
    payload.put("reason", reason);
    payload.put("proposedReference", proposedReference);
    return payload;
  }

  private static void writeNote(Path vault, String path, String body) throws Exception {
    Path target = vault.resolve(path);
    Files.createDirectories(target.getParent());
    Files.writeString(target, body, StandardCharsets.UTF_8);
  }

  private static void writeCatalog(Path review, String... refsAndPaths) throws Exception {
    Map<String, Object> entries = new java.util.LinkedHashMap<>();
    for (int i = 0; i < refsAndPaths.length; i += 2) {
      String title = title(refsAndPaths[i + 1]);
      entries.put(refsAndPaths[i], Map.of(
          "pageRef", refsAndPaths[i],
          "currentPath", refsAndPaths[i + 1],
          "stableNoteId", title,
          "title", title,
          "aliases", List.of(),
          "previousPaths", List.of(),
          "state", "active"));
    }
    Path path = review.resolve(".semantic-links/catalog-v1.json");
    Files.createDirectories(path.getParent());
    Files.writeString(path, JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "entries", entries)), StandardCharsets.UTF_8);
  }

  private static String title(String path) {
    String stem = path.replace(".md", "");
    int slash = stem.lastIndexOf('/');
    if (slash >= 0) {
      stem = stem.substring(slash + 1);
    }
    return stem.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + stem.substring(1);
  }

  private static void writeApproved(
      Path review,
      String collection,
      String publicId,
      String sourcePath,
      String pageRef,
      String ru,
      String en) throws Exception {
    Path published = review.resolve(collection).resolve(publicId).resolve("published");
    Files.createDirectories(published);
    Files.writeString(published.resolve("ru.md"), ru, StandardCharsets.UTF_8);
    Files.writeString(published.resolve("en.md"), en, StandardCharsets.UTF_8);
    Files.writeString(published.resolve("references.json"), JSON.writeValueAsString(Map.of(
        "schemaVersion", 1,
        "pageRef", pageRef,
        "sourcePath", sourcePath,
        "ruSha256", PageReferenceMapCodec.sha256(ru.getBytes(StandardCharsets.UTF_8)),
        "enSha256", PageReferenceMapCodec.sha256(en.getBytes(StandardCharsets.UTF_8)),
        "order", List.of(),
        "references", Map.of())), StandardCharsets.UTF_8);
  }

  private static Map<String, ByteBuffer> treeSnapshot(Path root) throws Exception {
    if (!Files.exists(root)) {
      return Map.of();
    }
    Map<String, ByteBuffer> snapshot = new java.util.TreeMap<>();
    try (var stream = Files.walk(root)) {
      for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
        snapshot.put(root.relativize(path).toString(), ByteBuffer.wrap(Files.readAllBytes(path)));
      }
    }
    return snapshot;
  }
}
