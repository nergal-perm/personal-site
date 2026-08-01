package dev.eugene.astroexport.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        "See [target](/en/target/).",
        List.of("ref-0001"));
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
                Map.of("start", 4, "end", 25),
                1,
                "vault-ref-target",
                null,
                "unique monotonic RU/EN/target alignment",
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
  void decisionDraftContainsReviewContextAndValidatedPageCorrectedPayload() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    Path inventoryReport = temp.resolve("inventory.json");
    Path decisions = temp.resolve("draft/decisions.json");
    writeNote(vault, "page.md", "[[Target|target]] [[Target|target]]");
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "[target](/ru/target/) [target](/ru/target/)",
        "[target](/en/target/) [target](/en/target/)",
        List.of("ref-0001", "ref-0002"));
    Map<String, ByteBuffer> beforeReview = treeSnapshot(review);
    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, inventoryReport);

    new SemanticDecisionDraftWriter().write(decisions, inventory, review);
    Map<String, Object> payload = JSON.readValue(Files.readAllBytes(decisions), new TypeReference<>() { });
    Map<?, ?> occurrence = (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) payload.get("pages")).getFirst())
        .get("occurrences")).getFirst();
    assertEquals("vault-ref-page/ref-0001", occurrence.get("occurrenceKey"));
    assertEquals("[[Target|target]]", occurrence.get("rawWikilink"));
    assertEquals("vault-ref-target", occurrence.get("targetRef"));
    assertTrue(occurrence.containsKey("heading"));
    assertTrue(occurrence.containsKey("reason"));
    assertTrue(occurrence.containsKey("sourceContext"));
    Map<?, ?> decision = (Map<?, ?>) ((Map<?, ?>) payload.get("decisions")).get("vault-ref-page/page");
    assertEquals("approve-corrected-page", decision.get("decision"));
    assertEquals(inventory.inventorySha256(), payload.get("inventorySha256"));
    assertEquals(true, payload.get("draftOnly"));
    ReferenceMigrationInventory.DecisionValidationException draftError = assertThrows(
        ReferenceMigrationInventory.DecisionValidationException.class,
        () -> new ReferenceMigrationInventory().validateDecisions(inventory, decisions));
    assertEquals("draft-not-converted", draftError.code());
    assertEquals(beforeReview, treeSnapshot(review));
  }

  @Test
  void unresolvedPageDraftContainsContextButNoExecutableDecision() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    Path decisions = temp.resolve("draft/decisions.json");
    writeNote(vault, "page.md", "[[Missing|missing]]");
    writeCatalog(review, "vault-ref-page", "page.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "[missing](/ru/missing/)", "[missing](/en/missing/)");

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("inventory.json"));
    assertEquals("unresolved", inventory.pages().getFirst().status().json());
    new SemanticDecisionDraftWriter().write(decisions, inventory, review);

    Map<String, Object> payload = JSON.readValue(Files.readAllBytes(decisions), new TypeReference<>() { });
    assertTrue(((Map<?, ?>) payload.get("decisions")).isEmpty());
    assertEquals("unresolved", ((Map<?, ?>) ((List<?>) payload.get("pages")).getFirst()).get("status"));
  }

  @Test
  void sidecarOrderMismatchPreventsExactAutomaticInventory() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    writeNote(vault, "page.md", "[[B|one]] [[C|two]]");
    writeNote(vault, "b.md", "B.");
    writeNote(vault, "c.md", "C.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-b", "b.md", "vault-ref-c", "c.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "[one](/ru/b/) [two](/ru/c/)",
        "[one](/en/b/) [two](/en/c/)",
        List.of("ref-0002", "ref-0001"));

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("inventory.json"));

    assertEquals("order-mismatch", inventory.pages().getFirst().status().json());
    assertEquals(List.of("order-mismatch", "order-mismatch"),
        inventory.pages().getFirst().occurrences().stream()
            .map(occurrence -> occurrence.classification().json())
            .toList());
  }

  @Test
  void emptySidecarOrderWithProposedOccurrencesIsOrderMismatch() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    writeNote(vault, "page.md", "[[Target|target]]");
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "[target](/ru/target/)",
        "[target](/en/target/)",
        List.of());

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("inventory.json"));

    assertEquals("order-mismatch", inventory.pages().getFirst().status().json());
    assertEquals("order-mismatch", inventory.pages().getFirst().occurrences().getFirst().classification().json());
    assertFalse(inventory.pages().getFirst().automatic());
  }

  @Test
  void nonemptySidecarOrderWithNoRawOccurrencesIsOrderMismatch() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    writeNote(vault, "page.md", "No current body links.");
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "No current body links.",
        "No current body links.",
        List.of("ref-0001"));

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("inventory.json"));

    assertEquals("order-mismatch", inventory.pages().getFirst().status().json());
    assertEquals("order-mismatch", inventory.pages().getFirst().occurrences().getFirst().classification().json());
    assertEquals("vault-ref-page/order", inventory.pages().getFirst().occurrences().getFirst().occurrenceKey());
    assertFalse(inventory.pages().getFirst().automatic());
  }

  @Test
  void missingCurrentSourceIsUnsafeInput() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "missing.md", "vault-ref-target", "target.md");
    writeApproved(review, "blog", "page", "missing.md", "vault-ref-page",
        "[target](/ru/target/)",
        "[target](/en/target/)");

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("inventory.json"));

    assertEquals("unsafe", inventory.pages().getFirst().status().json());
    assertFalse(inventory.pages().getFirst().automatic());
    assertEquals("unsafe-input", inventory.pages().getFirst().occurrences().getFirst().classification().json());
  }

  @Test
  void inventoriesLegacyTwoFilePairFromTheVaultPublicationIdentityWithoutCreatingCatalog() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    String sourcePath = "claims/Культура — равновесие структуры, а не свойство людей.md";
    writeNote(vault, sourcePath, """
        ---
        publish: true
        publicId: culture-as-selection-equilibrium
        publicCollection: blog
        publicContentType: essay
        ---
        Current source body.
        """);
    writeLegacyApprovedPair(review, "blog", "culture-as-selection-equilibrium");

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("inventory.json"));

    assertEquals(sourcePath, inventory.pages().getFirst().sourcePath());
    assertEquals("vault-ref-0001", inventory.pages().getFirst().pageRef());
    assertEquals("exact", inventory.pages().getFirst().status().json());
    assertFalse(Files.exists(review.resolve(".semantic-links/catalog-v1.json")));
  }

  @Test
  void rawFrontmatterLinksAreNotInventoried() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    writeNote(vault, "page.md", """
        ---
        related: [[Target|target]]
        ---
        Body without links.
        """);
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "Body without links.",
        "Body without links.");

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("inventory.json"));

    assertEquals(0, inventory.pages().getFirst().occurrences().size());
    assertEquals("exact", inventory.pages().getFirst().status().json());
  }

  @Test
  void inventoryUsesCurrentAstroRoutesInsteadOfVaultPathFallbacks() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    Path astro = temp.resolve("astro");
    writeNote(vault, "page.md", "[[Target|target]]");
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "[target](/ru/essays/current-target/)",
        "[target](/en/essays/current-target/)",
        List.of("ref-0001"));
    writeAstroRoute(astro, "src/content/blog/ru/current-target.md", "vault-ref-target", "/ru/essays/current-target/");
    writeAstroRoute(astro, "src/content/blog/en/current-target.md", "vault-ref-target", "/en/essays/current-target/");

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, astro, temp.resolve("inventory.json"));

    assertEquals("exact", inventory.pages().getFirst().status().json());
    assertEquals("exact", inventory.pages().getFirst().occurrences().getFirst().classification().json());
  }

  @Test
  void duplicateAstroRoutesForSamePageRefAndLanguageAreUnsafe() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    Path astro = temp.resolve("astro");
    writeNote(vault, "page.md", "[[Target|target]]");
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "[target](/ru/essays/first/)",
        "[target](/en/essays/target/)",
        List.of("ref-0001"));
    writeAstroRoute(astro, "src/content/blog/ru/first.md", "vault-ref-target", "/ru/essays/first/");
    writeAstroRoute(astro, "src/content/blog/ru/second.md", "vault-ref-target", "/ru/essays/second/");
    writeAstroRoute(astro, "src/content/blog/en/target.md", "vault-ref-target", "/en/essays/target/");

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, astro, temp.resolve("inventory.json"));

    assertEquals("unsafe", inventory.pages().getFirst().status().json());
    assertEquals("unsafe-input", inventory.pages().getFirst().occurrences().getFirst().classification().json());
    assertTrue(inventory.pages().getFirst().occurrences().getFirst().reason().contains("conflicting Astro routes"));
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
  void ambiguousTranslationWithNoProposedEnglishSpanCannotBeConfirmed() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    writeNote(vault, "page.md", "[[Target|target]] [[Target|target]]");
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "[target](/ru/target/) [target](/ru/target/)",
        "[target](/en/target/) [target](/en/target/)",
        List.of("ref-0001", "ref-0002"));

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("inventory.json"));
    ReferenceMigrationAligner.MigrationOccurrence occurrence =
        inventory.pages().getFirst().occurrences().getFirst();
    assertEquals("ambiguous-translation", occurrence.classification().json());
    assertNull(occurrence.proposedEnSpan());

    Path decisions = temp.resolve("decisions.json");
    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"vault-ref-page/ref-0001":{"decision":"confirm","enSpan":{"start":0,"end":1}}}}
        """.formatted(inventory.inventorySha256()));

    assertDecisionRejected(inventory, decisions, "hash-mismatch",
        "confirmed English span does not match inventory");
  }

  @Test
  void ambiguousTranslationConfirmationRequiresEnglishSpan() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    writeNote(vault, "page.md", "[[Target|target]] [[Target|target]]");
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "[target](/ru/target/) [target](/ru/target/)",
        "[target](/en/target/) [target](/en/target/)",
        List.of("ref-0001", "ref-0002"));

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("inventory.json"));
    Path decisions = temp.resolve("decisions.json");
    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"vault-ref-page/ref-0001":{"decision":"confirm"}}}
        """.formatted(inventory.inventorySha256()));

    assertDecisionRejected(inventory, decisions, "missing-en-span", "confirm requires enSpan");
  }

  @Test
  void correctedPageDecisionCoversAmbiguousOccurrencesAndBindsApprovedSnapshots() throws Exception {
    Path vault = temp.resolve("vault");
    Path review = temp.resolve("review");
    writeNote(vault, "page.md", "[[Target|target]] [[Target|target]]");
    writeNote(vault, "target.md", "Target.");
    writeCatalog(review, "vault-ref-page", "page.md", "vault-ref-target", "target.md");
    writeApproved(review, "blog", "page", "page.md", "vault-ref-page",
        "[target](/ru/target/) [target](/ru/target/)",
        "[target](/en/target/) [target](/en/target/)",
        List.of("ref-0001", "ref-0002"));

    ReferenceMigrationInventory.Inventory inventory =
        new ReferenceMigrationInventory().inspect(vault, review, temp.resolve("inventory.json"));
    assertEquals("confirmed-needed", inventory.pages().stream()
        .filter(page -> page.pageRef().equals("vault-ref-page"))
        .findFirst().orElseThrow().status().json());
    ReferenceMigrationAligner.MigrationPage page = inventory.pages().stream()
        .filter(candidate -> candidate.pageRef().equals("vault-ref-page"))
        .findFirst().orElseThrow();
    String correctedRussian = "[target](ref:ref-0001) [target](ref:ref-0002)";
    String correctedEnglish = "[target](ref:ref-0001) [target](ref:ref-0002)";
    Path correctedRu = temp.resolve("corrected/page-ru.md");
    Path correctedEn = temp.resolve("corrected/page-en.md");
    Files.createDirectories(correctedRu.getParent());
    Files.writeString(correctedRu, correctedRussian, StandardCharsets.UTF_8);
    Files.writeString(correctedEn, correctedEnglish, StandardCharsets.UTF_8);
    Path decisions = temp.resolve("decisions-page.json");
    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"vault-ref-page/page":{"decision":"approve-corrected-page","correctedRussianPath":"%s","correctedEnglishPath":"%s","approvedRussianSha256":"%s","approvedEnglishSha256":"%s","correctedRussianSha256":"%s","correctedEnglishSha256":"%s"}}}
        """.formatted(
            inventory.inventorySha256(),
            temp.relativize(correctedRu),
            temp.relativize(correctedEn),
            PageReferenceMapCodec.sha256(page.approvedRussian().text().getBytes(StandardCharsets.UTF_8)),
            PageReferenceMapCodec.sha256(page.approvedEnglish().text().getBytes(StandardCharsets.UTF_8)),
            PageReferenceMapCodec.sha256(correctedRussian.getBytes(StandardCharsets.UTF_8)),
            PageReferenceMapCodec.sha256(correctedEnglish.getBytes(StandardCharsets.UTF_8))),
        StandardCharsets.UTF_8);

    ReferenceMigrationInventory.DecisionSet decisionSet =
        new ReferenceMigrationInventory().validateDecisions(inventory, decisions);
    assertEquals(List.of("vault-ref-page/page"), decisionSet.keys());
    assertEquals(correctedEnglish,
        new String(decisionSet.correctedPages().get("vault-ref-page/page").correctedEnglishBytes(),
            StandardCharsets.UTF_8));

    Files.writeString(review.resolve("blog/page/published/en.md"), "changed approved English",
        StandardCharsets.UTF_8);
    assertDecisionRejected(inventory, decisions, "hash-mismatch");
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
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"vault-ref-page/order":{"decision":"approve-corrected-order","correctedEnglishPath":"corrected/page-en.md","approvedEnglishSha256":"%s","correctedEnglishSha256":"%s"}}}
        """.formatted(inventory.inventorySha256(), approvedEnglishHash(inventory), PageReferenceMapCodec.sha256(Files.readAllBytes(corrected))));

    ReferenceMigrationInventory.DecisionSet decisionSet =
        new ReferenceMigrationInventory().validateDecisions(inventory, decisions);

    assertEquals(List.of("vault-ref-page/order"), decisionSet.keys());
    ReferenceMigrationInventory.CorrectedOrderDecision correctedDecision =
        (ReferenceMigrationInventory.CorrectedOrderDecision) decisionSet.decisions().getFirst();
    assertEquals("corrected/page-en.md", correctedDecision.correctedEnglishPath());
    assertEquals("[one](/en/b/) [two](/en/c/)",
        new String(correctedDecision.correctedEnglishBytes(), StandardCharsets.UTF_8));

    Files.writeString(corrected, "changed", StandardCharsets.UTF_8);
    assertDecisionRejected(inventory, decisions, "hash-mismatch");
  }

  @Test
  void correctedEnglishDecisionRejectsUnchangedReversedOrder() throws Exception {
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
    Files.writeString(corrected, "[two](/en/c/) [one](/en/b/)", StandardCharsets.UTF_8);
    Path decisions = temp.resolve("decisions.json");
    Files.writeString(decisions, """
        {"schemaVersion":1,"inventorySha256":"%s","decisions":{"vault-ref-page/order":{"decision":"approve-corrected-order","correctedEnglishPath":"corrected/page-en.md","approvedEnglishSha256":"%s","correctedEnglishSha256":"%s"}}}
        """.formatted(inventory.inventorySha256(), approvedEnglishHash(inventory), PageReferenceMapCodec.sha256(Files.readAllBytes(corrected))));

    assertDecisionRejected(inventory, decisions, "order-mismatch");
  }

  private static void assertDecisionRejected(
      ReferenceMigrationInventory.Inventory inventory,
      Path decisions,
      String code) {
    assertDecisionRejected(inventory, decisions, code, null);
  }

  private static String approvedEnglishHash(ReferenceMigrationInventory.Inventory inventory) {
    return PageReferenceMapCodec.sha256(
        inventory.pages().getFirst().approvedEnglish().text().getBytes(StandardCharsets.UTF_8));
  }

  private static void assertDecisionRejected(
      ReferenceMigrationInventory.Inventory inventory,
      Path decisions,
      String code,
      String message) {
    ReferenceMigrationInventory.DecisionValidationException error = assertThrows(
        ReferenceMigrationInventory.DecisionValidationException.class,
        () -> new ReferenceMigrationInventory().validateDecisions(inventory, decisions));
    assertEquals(code, error.code());
    if (message != null) {
      assertEquals(message, error.getMessage());
    }
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
      Map<String, Object> proposedEnSpan,
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
    payload.put("proposedEnSpan", proposedEnSpan);
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
    writeApproved(review, collection, publicId, sourcePath, pageRef, ru, en, List.of());
  }

  private static void writeApproved(
      Path review,
      String collection,
      String publicId,
      String sourcePath,
      String pageRef,
      String ru,
      String en,
      List<String> order) throws Exception {
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
        "order", order,
        "references", Map.of())), StandardCharsets.UTF_8);
  }

  private static void writeLegacyApprovedPair(Path review, String collection, String publicId) throws Exception {
    Path published = review.resolve(collection).resolve(publicId).resolve("published");
    Files.createDirectories(published);
    Files.writeString(published.resolve("ru.md"), "Approved Russian.\n", StandardCharsets.UTF_8);
    Files.writeString(published.resolve("en.md"), "Approved English.\n", StandardCharsets.UTF_8);
  }

  private static void writeAstroRoute(
      Path astro,
      String path,
      String pageRef,
      String route) throws Exception {
    Path target = astro.resolve(path);
    Files.createDirectories(target.getParent());
    Files.writeString(target, """
        ---
        pageRef: %s
        route: %s
        ---
        Body.
        """.formatted(pageRef, route), StandardCharsets.UTF_8);
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
