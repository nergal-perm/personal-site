package dev.eugene.astroexport.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.discovery.PublicationDiscovery;
import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
import dev.eugene.astroexport.fs.SiteWriter;
import dev.eugene.astroexport.manifest.ManifestBuilder;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestResult;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.prepare.PrepareWorkflow;
import dev.eugene.astroexport.review.ReviewWorkspace;
import dev.eugene.astroexport.testsupport.CommandFixture;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AstroExportCommandTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> BRIDGE_KEYS = List.of(
      "schemaVersion",
      "command",
      "ok",
      "status",
      "note",
      "collection",
      "publicId",
      "reviewDirectory",
      "pairFreshness",
      "translationStatus",
      "diagnostics",
      "workspaceHealth",
      "jobId",
      "summary",
      "updated",
      "unchanged",
      "uncertain");

  @TempDir
  Path temp;

  @Test
  void inspectBridgeHasExactSchemaAndIsReadOnlyWithWorkspaceHealth() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault);
    Path unrelated = vault.resolve("other/Broken.md");
    Files.createDirectories(unrelated.getParent());
    Files.writeString(unrelated, """
        ---
        publish: true
        publicCollection: blog
        publicContentType: essay
        ---
        Broken.
        """);
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    Map<String, ByteBuffer> beforeVault = treeSnapshot(vault);
    Map<String, ByteBuffer> beforeReview = treeSnapshot(review);

    CommandFixture.Result result = run(command(),
        "inspect-publication",
        "--vault", vault.toString(),
        "--note", "anywhere/Essay.md",
        "--review", review.toString(),
        "--json");

    assertEquals(0, result.exitCode());
    Map<String, Object> payload = json(result.stdout());
    assertIterableEquals(BRIDGE_KEYS, payload.keySet());
    assertEquals(1, payload.get("schemaVersion"));
    assertEquals("inspect-publication", payload.get("command"));
    assertEquals(true, payload.get("ok"));
    assertEquals("ready_for_review", payload.get("status"));
    assertEquals("anywhere/Essay.md", payload.get("note"));
    assertEquals("blog", payload.get("collection"));
    assertEquals("essay", payload.get("publicId"));
    assertEquals(review.resolve("blog/essay").toString(), payload.get("reviewDirectory"));
    assertEquals("fresh", payload.get("pairFreshness"));
    assertEquals("generated", payload.get("translationStatus"));
    assertEquals(List.of(), payload.get("diagnostics"));
    assertEquals(List.of(Map.of(
        "field", "publicId",
        "message", "other/Broken.md: must be a lowercase route slug",
        "blocking", true)), payload.get("workspaceHealth"));
    assertEquals(null, payload.get("jobId"));
    assertEquals(null, payload.get("summary"));
    assertEquals(null, payload.get("updated"));
    assertEquals(null, payload.get("unchanged"));
    assertEquals(null, payload.get("uncertain"));
    assertEquals(beforeVault, treeSnapshot(vault));
    assertEquals(beforeReview, treeSnapshot(review));
  }

  @Test
  void prepareBridgeUsesStableSchemaAndParserRejectsReleaseOptions() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault);
    ManifestEntry entry = currentBlogEntry(vault);
    Path review = temp.resolve("review");
    Path jobs = temp.resolve("jobs");
    CommandServices services = CommandServices.defaults()
        .withClock(Clock.fixed(Instant.parse("2026-07-18T12:30:00Z"), ZoneOffset.UTC))
        .withPrepareAction((actualVault, note, actualReview, actualJobs, entryResolver) -> {
          assertEquals(vault, actualVault);
          assertEquals("anywhere/Essay.md", note);
          assertEquals(review, actualReview);
          assertEquals(jobs, actualJobs);
          return new PrepareWorkflow.PrepareResult(
              "ready_for_review",
              entry,
              List.of(),
              List.of(new dev.eugene.astroexport.validation.PublicationDiagnostic(
                  "publicId", "other/Bad.md: missing publicId")),
              review.resolve("blog/essay"),
              "job-123");
        });

    CommandFixture.Result result = run(new AstroExportCommand(services),
        "prepare",
        "--vault", vault.toString(),
        "--note", "anywhere/Essay.md",
        "--review", review.toString(),
        "--jobs", jobs.toString(),
        "--json");

    assertEquals(0, result.exitCode());
    Map<String, Object> payload = json(result.stdout());
    assertIterableEquals(BRIDGE_KEYS, payload.keySet());
    assertEquals(true, payload.get("ok"));
    assertEquals("ready_for_review", payload.get("status"));
    assertEquals("fresh", payload.get("pairFreshness"));
    assertEquals("generated", payload.get("translationStatus"));
    assertEquals("job-123", payload.get("jobId"));
    assertEquals(null, payload.get("summary"));

    CommandFixture.Result rejected = run(command(),
        "prepare",
        "--vault", vault.toString(),
        "--note", "anywhere/Essay.md",
        "--review", review.toString(),
        "--jobs", jobs.toString(),
        "--json",
        "--out", temp.resolve("astro").toString());
    assertEquals(2, rejected.exitCode());
  }

  @Test
  void markReviewedAtomicallyReviewsGeneratedPairAndRefreshReportsSixStateSummary() throws Exception {
    Path vault = temp.resolve("vault");
    Path source = writeBlogNote(vault);
    ManifestEntry entry = currentBlogEntry(vault);
    Path review = temp.resolve("review");
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    Path jobs = temp.resolve("jobs");

    CommandFixture.Result marked = run(command(),
        "mark-reviewed",
        "--vault", vault.toString(),
        "--note", "anywhere/Essay.md",
        "--review", review.toString(),
        "--jobs", jobs.toString(),
        "--json");

    assertEquals(0, marked.exitCode(), marked.stderr());
    Map<String, Object> payload = json(marked.stdout());
    assertIterableEquals(BRIDGE_KEYS, payload.keySet());
    assertEquals(true, payload.get("ok"));
    assertEquals("ready_to_publish", payload.get("status"));
    assertEquals("fresh", payload.get("pairFreshness"));
    assertEquals("reviewed", payload.get("translationStatus"));
    String reviewed = Files.readString(review.resolve("blog/essay/en.md"));
    assertTrue(reviewed.contains("translationStatus: \"reviewed\""));
    String sourceText = Files.readString(source);
    assertTrue(sourceText.contains("publicWorkflowStatus: \"ready_to_publish\""));
    assertTrue(sourceText.contains("publicTranslationStatus: \"reviewed\""));

    CommandFixture.Result refreshed = run(command(),
        "refresh-publication-queue",
        "--vault", vault.toString(),
        "--review", review.toString(),
        "--jobs", jobs.toString(),
        "--json");

    assertEquals(0, refreshed.exitCode(), refreshed.stderr());
    Map<String, Object> refreshPayload = json(refreshed.stdout());
    assertIterableEquals(BRIDGE_KEYS, refreshPayload.keySet());
    assertEquals("refreshed", refreshPayload.get("status"));
    assertEquals(Map.of(
        "metadata_blocked", 0,
        "translating", 0,
        "ready_for_review", 0,
        "ready_to_publish", 1,
        "translation_failed", 0,
        "stale", 0), refreshPayload.get("summary"));
    assertEquals(0, refreshPayload.get("updated"));
    assertEquals(1, refreshPayload.get("unchanged"));
    assertEquals(0, refreshPayload.get("uncertain"));
  }

  @Test
  void markReviewedDoesNotCommitEnglishWhenSourceChangesBeforeEnglishCommit() throws Exception {
    Path vault = temp.resolve("vault");
    Path source = writeBlogNote(vault);
    ManifestEntry entry = currentBlogEntry(vault);
    Path review = temp.resolve("review");
    Path en = writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    byte[] enBefore = Files.readAllBytes(en);
    Path jobs = temp.resolve("jobs");
    CommandServices defaults = CommandServices.defaults();
    CommandServices services = defaults.withReplaceEnglishReviewAction(
        (actualReview, content, collection, publicId, expected, guards) -> {
          Files.writeString(
              source,
              Files.readString(source).replace("Text.", "Source changed before English commit."));
          return defaults.replaceEnglishReview(actualReview, content, collection, publicId, expected, guards);
        });

    CommandFixture.Result result = run(new AstroExportCommand(services),
        "mark-reviewed",
        "--vault", vault.toString(),
        "--note", "anywhere/Essay.md",
        "--review", review.toString(),
        "--jobs", jobs.toString(),
        "--json");

    assertEquals(1, result.exitCode());
    Map<String, Object> payload = json(result.stdout());
    assertEquals(false, payload.get("ok"));
    assertEquals("stale", payload.get("status"));
    assertEquals(ByteBuffer.wrap(enBefore), ByteBuffer.wrap(Files.readAllBytes(en)));
    assertFalse(Files.readString(en).contains("translationStatus: \"reviewed\""));
    assertTrue(Files.readString(source).contains("Source changed before English commit."));
  }

  @Test
  void bridgeCommandsPropagateProgrammerErrors() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault);
    CommandServices inspectServices = CommandServices.defaults()
        .withPreflightObserver((actualVault, note) -> {
          throw new RuntimeException("unexpected inspect invariant failure");
        });
    RuntimeException inspect = assertThrows(RuntimeException.class,
        () -> run(new AstroExportCommand(inspectServices),
            "inspect-publication",
            "--vault", vault.toString(),
            "--note", "anywhere/Essay.md",
            "--json"));
    assertTrue(inspect.getMessage().contains("inspect invariant"));

    CommandServices refreshServices = CommandServices.defaults()
        .withSelectionAction(actualVault -> {
          throw new AssertionError("unexpected selection invariant failure");
        });
    AssertionError refresh = assertThrows(AssertionError.class, () -> run(new AstroExportCommand(refreshServices),
        "refresh-publication-queue",
        "--vault", vault.toString(),
        "--json"));
    assertTrue(refresh.getMessage().contains("selection invariant"));
  }

  @Test
  void prepareBridgeReportsExpectedDiscoveryFailureFromIdentityFallbackAsJson() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault);
    CommandServices services = CommandServices.defaults()
        .withPrepareAction((actualVault, note, review, jobs, entryResolver) -> new PrepareWorkflow.PrepareResult(
            "metadata_blocked", null, List.of(), List.of(), null, null))
        .withSelectionAction(actualVault -> {
          throw new PublicationDiscovery.PublicationSearchException("expected discovery failure");
        });

    CommandFixture.Result result = run(new AstroExportCommand(services),
        "prepare",
        "--vault", vault.toString(),
        "--note", "anywhere/Essay.md",
        "--json");

    assertNonRefreshBridgeIoFailure(result, "prepare", "translation_failed", "anywhere/Essay.md");
  }

  @Test
  void inspectBridgeReportsExpectedDiscoveryFailureAsJson() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault);
    CommandServices services = CommandServices.defaults()
        .withSelectionAction(actualVault -> {
          throw new PublicationDiscovery.PublicationSearchException("expected discovery failure");
        });

    CommandFixture.Result result = run(new AstroExportCommand(services),
        "inspect-publication",
        "--vault", vault.toString(),
        "--note", "anywhere/Essay.md",
        "--json");

    assertNonRefreshBridgeIoFailure(result, "inspect-publication", "metadata_blocked", "anywhere/Essay.md");
  }

  @Test
  void markReviewedBridgeReportsExpectedDiscoveryFailureAsJson() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault);
    CommandServices services = CommandServices.defaults()
        .withSelectionAction(actualVault -> {
          throw new PublicationDiscovery.PublicationSearchException("expected discovery failure");
        });

    CommandFixture.Result result = run(new AstroExportCommand(services),
        "mark-reviewed",
        "--vault", vault.toString(),
        "--note", "anywhere/Essay.md",
        "--json");

    assertNonRefreshBridgeIoFailure(result, "mark-reviewed", "stale", "anywhere/Essay.md");
  }

  @Test
  void refreshBridgeReportsMissingVaultDiscoveryFailureAsJson() throws Exception {
    Path vault = temp.resolve("missing-vault");

    CommandFixture.Result result = run(command(),
        "refresh-publication-queue",
        "--vault", vault.toString(),
        "--json");

    assertEquals(1, result.exitCode());
    assertEquals(1, result.stdout().lines().count());
    assertFalse(result.stdout().contains("Traceback"));
    assertFalse(result.stderr().contains("Traceback"));
    Map<String, Object> payload = json(result.stdout());
    assertIterableEquals(BRIDGE_KEYS, payload.keySet());
    assertEquals(1, payload.get("schemaVersion"));
    assertEquals("refresh-publication-queue", payload.get("command"));
    assertEquals(false, payload.get("ok"));
    assertEquals("refresh_failed", payload.get("status"));
    assertEquals(null, payload.get("note"));
    assertEquals(null, payload.get("collection"));
    assertEquals(null, payload.get("publicId"));
    assertEquals(null, payload.get("reviewDirectory"));
    assertEquals(null, payload.get("pairFreshness"));
    assertEquals(null, payload.get("translationStatus"));
    assertEquals(List.of(Map.of(
        "field", "io",
        "message", "Could not read publication files: PublicationSearchException.",
        "blocking", true)), payload.get("diagnostics"));
    assertEquals(List.of(), payload.get("workspaceHealth"));
    assertEquals(null, payload.get("jobId"));
    assertEquals(Map.of(
        "metadata_blocked", 0,
        "translating", 0,
        "ready_for_review", 0,
        "ready_to_publish", 0,
        "translation_failed", 0,
        "stale", 0), payload.get("summary"));
    assertEquals(0, payload.get("updated"));
    assertEquals(0, payload.get("unchanged"));
    assertEquals(0, payload.get("uncertain"));
  }

  @Test
  void dryRunWritesReportWithoutOutReviewGateOrTracebackOnTranslationBlocker() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault);
    Path review = temp.resolve("review");
    Path out = temp.resolve("astro");
    Path report = temp.resolve("dry-report.md");
    AtomicInteger gates = new AtomicInteger();
    CommandServices services = CommandServices.defaults()
        .withGateRunner(invocation -> {
          gates.incrementAndGet();
          return new SiteWriter.GateResult(0, "", "");
        });

    CommandFixture.Result result = run(new AstroExportCommand(services),
        "--vault", vault.toString(),
        "--out", out.toString(),
        "--dry-run",
        "--report", report.toString(),
        "--review", review.toString());

    assertEquals(1, result.exitCode());
    String text = Files.readString(report);
    assertTrue(text.contains("Translation blockers (1)"));
    assertTrue(text.contains("missing translation"));
    assertFalse(text.contains("Traceback"));
    assertTrue(result.stdout().contains(text));
    assertEquals(0, gates.get());
    assertFalse(Files.exists(out));
    assertFalse(Files.exists(review.resolve("blog/essay/ru.md")));
  }

  @Test
  void buildFromReviewRefreshesRuKeepsEnAndRunsGateAgainstStage() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault, "Before ![[media/cover.png]] after.");
    Path asset = vault.resolve("media/cover.png");
    Files.createDirectories(asset.getParent());
    Files.write(asset, "asset bytes\n".getBytes(StandardCharsets.UTF_8));
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    Path en = writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    byte[] enBefore = Files.readAllBytes(en);
    Path out = writeAstroRoot(temp.resolve("astro"));
    Path report = temp.resolve("write-report.md");
    List<Map<String, Object>> gateCalls = new java.util.ArrayList<>();
    CommandServices services = CommandServices.defaults()
        .withGateRunner(invocation -> {
          Path contentDir = Path.of(invocation.environment().get("ASTRO_CONTENT_DIR"));
          Path pagesDir = Path.of(invocation.environment().get("ASTRO_PAGES_DIR"));
          Path stage = contentDir.getParent().getParent();
          assertEquals(stage, pagesDir.getParent().getParent().getParent());
          assertFalse(stage.equals(out));
          assertTrue(Files.isRegularFile(contentDir.resolve("blog/ru/essay.md")));
          assertTrue(Files.isRegularFile(pagesDir.resolve("ru/search.json")));
          gateCalls.add(Map.of(
              "command", invocation.command(),
              "cwd", invocation.workingDirectory(),
              "ci", invocation.environment().get("CI"),
              "noColor", invocation.environment().get("NO_COLOR")));
          return new SiteWriter.GateResult(0, "gate ok\n", "");
        });

    CommandFixture.Result result = run(new AstroExportCommand(services),
        "build-from-review",
        "--vault", vault.toString(),
        "--out", out.toString(),
        "--report", report.toString(),
        "--review", review.toString());

    assertEquals(0, result.exitCode(), result.stderr());
    assertEquals(List.of(Map.of(
        "command", List.of("npm", "run", "check"),
        "cwd", out.toRealPath(),
        "ci", "1",
        "noColor", "1")), gateCalls);
    assertTrue(Files.isRegularFile(review.resolve("blog/essay/ru.md")));
    assertEquals(ByteBuffer.wrap(enBefore), ByteBuffer.wrap(Files.readAllBytes(en)));
    String reportText = Files.readString(report);
    assertTrue(reportText.contains("Generated records: 4"));
    assertTrue(reportText.contains("Vault reference `media/cover.png`"));
    assertTrue(Files.isRegularFile(out.resolve("src/content/blog/ru/essay.md")));
    assertEquals("keep me\n", Files.readString(out.resolve("unmanaged.txt")));
    assertTrue(temporarySiblings(out).isEmpty());
  }

  @Test
  void buildFromReviewWritesPublishedSnapshotOfRuAndEnAfterSuccessfulWrite() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault, "Published body.");
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    Path out = writeAstroRoot(temp.resolve("astro"));
    Path report = temp.resolve("write-report.md");
    CommandServices services = CommandServices.defaults()
        .withGateRunner(invocation -> new SiteWriter.GateResult(0, "gate ok\n", ""));

    CommandFixture.Result result = run(new AstroExportCommand(services),
        "build-from-review",
        "--vault", vault.toString(),
        "--out", out.toString(),
        "--report", report.toString(),
        "--review", review.toString());

    assertEquals(0, result.exitCode(), result.stderr());
    Path publishedRu = review.resolve("blog/essay/published/ru.md");
    Path publishedEn = review.resolve("blog/essay/published/en.md");
    assertTrue(Files.isRegularFile(publishedRu));
    assertTrue(Files.isRegularFile(publishedEn));
    assertEquals(
        Files.readString(review.resolve("blog/essay/ru.md")),
        Files.readString(publishedRu));
    assertEquals(
        Files.readString(review.resolve("blog/essay/en.md")),
        Files.readString(publishedEn));
  }

  @Test
  void buildFromReviewReportsCommittedWriteErrorWhenSnapshotPublishedFails() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault, "Published body.");
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    Path out = writeAstroRoot(temp.resolve("astro"));
    Path report = temp.resolve("write-report.md");
    CommandServices services = CommandServices.defaults()
        .withGateRunner(invocation -> new SiteWriter.GateResult(0, "gate ok\n", ""))
        .withSnapshotPublishedAction((reviewRoot, manifest) -> {
          throw new RuntimeException("boom");
        });

    CommandFixture.Result result = run(new AstroExportCommand(services),
        "build-from-review",
        "--vault", vault.toString(),
        "--out", out.toString(),
        "--report", report.toString(),
        "--review", review.toString());

    assertEquals(1, result.exitCode(), result.stderr());
    String reportText = Files.readString(report);
    assertTrue(reportText.startsWith("# Astro export committed with errors\n"));
    assertTrue(reportText.contains("Status: committed-with-errors"));
    assertTrue(reportText.contains("boom"));
  }

  @Test
  void dryRunBuildFromReviewDoesNotWritePublishedSnapshot() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault, "Not yet published.");
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    Path report = temp.resolve("dry-run-report.md");

    CommandFixture.Result result = run(new AstroExportCommand(CommandServices.defaults()),
        "build-from-review",
        "--vault", vault.toString(),
        "--dry-run",
        "--report", report.toString(),
        "--review", review.toString());

    assertEquals(0, result.exitCode(), result.stderr());
    assertFalse(Files.exists(review.resolve("blog/essay/published")));
  }

  @Test
  void writeModeRejectsMissingOutInvalidAstroRootAndReportUnderOutBeforeSelection() throws Exception {
    Path vault = temp.resolve("vault");
    Files.createDirectories(vault);
    Path report = temp.resolve("blocked-report.md");

    CommandFixture.Result missingOut = run(command(),
        "--vault", vault.toString(),
        "--report", report.toString());

    assertEquals(1, missingOut.exitCode());
    assertTrue(Files.readString(report).contains("Write mode requires --out <Astro root>"));
    assertFalse(missingOut.stderr().contains("Exception"));

    Path out = writeAstroRoot(temp.resolve("astro"));
    Files.delete(out.resolve("scripts/check-content.mjs"));
    CommandFixture.Result invalidRoot = run(command(),
        "--vault", vault.toString(),
        "--out", out.toString(),
        "--report", temp.resolve("invalid-root.md").toString());

    assertEquals(1, invalidRoot.exitCode());
    assertTrue(invalidRoot.stdout().contains("invalid Astro root"));
    assertTrue(invalidRoot.stdout().contains("scripts/check-content.mjs"));

    AtomicInteger selected = new AtomicInteger();
    CommandServices services = CommandServices.defaults()
        .withSelectionAction(actualVault -> {
          selected.incrementAndGet();
          throw new AssertionError("report containment must fail before selection");
        });
    Path containedReport = out.resolve("reports/report.md");
    CommandFixture.Result contained = run(new AstroExportCommand(services),
        "--vault", vault.toString(),
        "--out", out.toString(),
        "--report", containedReport.toString());

    assertEquals(1, contained.exitCode());
    assertEquals(0, selected.get());
    assertTrue(contained.stdout().contains("--report must resolve outside --out"));
    assertFalse(Files.exists(containedReport));
  }

  @Test
  void writerFailureAndAssetBlockersPreserveLiveTreesAndReportsNoTraceback() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault, "Missing ![[media/missing.png]].");
    Path review = temp.resolve("review");
    writeBlogReviewEn(review, currentBlogEntry(vault).translationSourceHash(), "generated");
    Path out = writeAstroRoot(temp.resolve("astro"));
    Map<String, ByteBuffer> before = managedSnapshot(out);
    Path report = temp.resolve("asset-blocked.md");

    CommandFixture.Result assetBlocked = run(command(),
        "--vault", vault.toString(),
        "--out", out.toString(),
        "--report", report.toString(),
        "--review", review.toString());

    assertEquals(1, assetBlocked.exitCode());
    String blockedReport = Files.readString(report);
    assertTrue(blockedReport.contains("media/missing.png"));
    assertTrue(blockedReport.contains("missing file"));
    assertFalse((blockedReport + assetBlocked.stdout() + assetBlocked.stderr()).contains("Traceback"));
    assertEquals(before, managedSnapshot(out));

    writeBlogNote(vault, "Text.");
    writeBlogReviewEn(review, currentBlogEntry(vault).translationSourceHash(), "generated");
    Path recovery = temp.resolve(".astro.astro-export-backup-recovery");
    CommandServices services = CommandServices.defaults()
        .withWriteSiteAction((siteRoot, manifest, validator) -> {
          throw new SiteWriter.WriterException("committed cleanup failed", true, List.of(recovery.toString()));
        });
    CommandFixture.Result committed = run(new AstroExportCommand(services),
        "--vault", vault.toString(),
        "--out", out.toString(),
        "--report", temp.resolve("committed.md").toString(),
        "--review", review.toString());

    assertEquals(1, committed.exitCode());
    assertTrue(committed.stdout().startsWith("# Astro export committed with errors\n"));
    assertTrue(committed.stdout().contains("Status: committed-with-errors"));
    assertTrue(committed.stdout().contains("committed cleanup failed"));
    assertTrue(committed.stdout().contains(recovery.toString()));
  }

  @Test
  void migrateOverridesAndPublicationContractCommandsMatchOperatorSurface() throws Exception {
    Path overrides = temp.resolve("overrides/en/blog");
    Files.createDirectories(overrides);
    Files.writeString(overrides.resolve("essay.md"), "override\n");
    Path review = temp.resolve("review");

    CommandFixture.Result migrated = run(command(),
        "migrate-overrides",
        "--overrides", temp.resolve("overrides/en").toString(),
        "--review", review.toString());

    assertEquals(0, migrated.exitCode());
    assertEquals(review.resolve("blog/essay/en.md") + "\n", migrated.stdout());
    assertEquals("override\n", Files.readString(review.resolve("blog/essay/en.md")));

    Path destination = temp.resolve("docs/publication.md");
    CommandFixture.Result contract = run(command(),
        "write-publication-contract",
        "--out", destination.toString());

    assertEquals(0, contract.exitCode());
    assertEquals(destination + "\n", contract.stdout());
    String text = Files.readString(destination);
    assertTrue(text.startsWith("---\ntitle: Подготовка заметок к публикации на Astro-сайте\n"));
    assertTrue(text.contains("### blog/essay"));
    assertTrue(text.contains("| `ready_to_publish` | Английский текст вручную проверен"));
    assertTrue(text.endsWith("\n"));
  }

  @Test
  void writePublicationContractSupportsParentlessOutputPath() throws Exception {
    Path destination = Path.of(".astro-export-command-test-publication-contract-"
        + System.nanoTime() + ".md");
    try {
      CommandFixture.Result result = run(command(),
          "write-publication-contract",
          "--out", destination.toString());

      assertEquals(0, result.exitCode());
      assertEquals(destination + "\n", result.stdout());
      assertTrue(Files.readString(destination).contains("### blog/essay"));
    } finally {
      Files.deleteIfExists(destination);
    }
  }

  private static AstroExportCommand command() {
    return new AstroExportCommand();
  }

  private static CommandFixture.Result run(AstroExportCommand command, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    var commandLine = AstroExportCommand.commandLine(command);
    commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
    commandLine.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
    int exitCode = commandLine.execute(args);
    return new CommandFixture.Result(
        exitCode,
        out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }

  private static Map<String, Object> json(String stdout) throws Exception {
    String stripped = stdout.strip();
    assertFalse(stripped.contains("\n"), "bridge stdout must be one JSON object");
    return JSON.readValue(stripped, new TypeReference<LinkedHashMap<String, Object>>() { });
  }

  private static void assertNonRefreshBridgeIoFailure(
      CommandFixture.Result result,
      String command,
      String status,
      String note) throws Exception {
    assertEquals(1, result.exitCode());
    assertEquals(1, result.stdout().lines().count());
    assertFalse(result.stdout().contains("Traceback"));
    assertFalse(result.stderr().contains("Traceback"));
    Map<String, Object> payload = json(result.stdout());
    assertIterableEquals(BRIDGE_KEYS, payload.keySet());
    assertEquals(1, payload.get("schemaVersion"));
    assertEquals(command, payload.get("command"));
    assertEquals(false, payload.get("ok"));
    assertEquals(status, payload.get("status"));
    assertEquals(note, payload.get("note"));
    assertEquals(null, payload.get("collection"));
    assertEquals(null, payload.get("publicId"));
    assertEquals(null, payload.get("reviewDirectory"));
    assertEquals(null, payload.get("pairFreshness"));
    assertEquals(null, payload.get("translationStatus"));
    assertEquals(List.of(Map.of(
        "field", "io",
        "message", "Could not read publication files: PublicationSearchException.",
        "blocking", true)), payload.get("diagnostics"));
    assertEquals(List.of(), payload.get("workspaceHealth"));
    assertEquals(null, payload.get("jobId"));
    assertEquals(null, payload.get("summary"));
    assertEquals(null, payload.get("updated"));
    assertEquals(null, payload.get("unchanged"));
    assertEquals(null, payload.get("uncertain"));
  }

  private static Path writeBlogNote(Path vault) throws Exception {
    return writeBlogNote(vault, "Text.");
  }

  private static Path writeBlogNote(Path vault, String body) throws Exception {
    Path path = vault.resolve("anywhere/Essay.md");
    Files.createDirectories(path.getParent());
    Files.writeString(path, """
        ---
        id: essay-internal
        title: Essay
        publish: true
        publicId: essay
        publicCollection: blog
        publicContentType: essay
        description: Essay.
        ---
        %s
        """.formatted(body));
    return path;
  }

  private static ManifestEntry currentBlogEntry(Path vault) {
    SelectionResult selection = CommandServices.defaults().select(vault);
    ManifestResult manifest = new ManifestBuilder().buildRussianManifest(selection);
    return manifest.entries().stream()
        .filter(entry -> entry.sourcePath().equals("anywhere/Essay.md"))
        .findFirst()
        .orElseThrow();
  }

  private static Path writeBlogReviewEn(Path reviewRoot, String sourceHash, String status) throws Exception {
    Path path = reviewRoot.resolve("blog/essay/en.md");
    Files.createDirectories(path.getParent());
    Files.writeString(path, """
        ---
        sourceHash: %s
        translationStatus: %s
        translatedAt: 2026-07-17
        translationProfile: codex-test-v1
        title: English title
        description: English description.
        ---
        English body.
        """.formatted(sourceHash, status));
    return path;
  }

  private static Path writeAstroRoot(Path root) throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.createDirectories(root.resolve("scripts"));
    Files.createDirectories(root.resolve("public/assets/vault"));
    Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"check\":\"true\"}}\n");
    Files.writeString(root.resolve("src/content.config.ts"), "export default {};\n");
    Files.writeString(root.resolve("scripts/check-content.mjs"), "console.log('check');\n");
    Files.writeString(root.resolve("unmanaged.txt"), "keep me\n");
    Files.createDirectories(root.resolve("src/content/blog/ru"));
    Files.writeString(root.resolve("src/content/blog/ru/old.md"), "old\n");
    return root;
  }

  private static Map<String, ByteBuffer> treeSnapshot(Path root) throws Exception {
    if (!Files.exists(root)) {
      return Map.of();
    }
    LinkedHashMap<String, ByteBuffer> snapshot = new LinkedHashMap<>();
    try (var paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
        snapshot.put(root.relativize(path).toString(), ByteBuffer.wrap(Files.readAllBytes(path)));
      }
    }
    return snapshot;
  }

  private static Map<String, ByteBuffer> managedSnapshot(Path root) throws Exception {
    LinkedHashMap<String, ByteBuffer> snapshot = new LinkedHashMap<>();
    for (String managed : List.of("src/content", "src/data/pages", "public/assets/vault")) {
      Path base = root.resolve(managed);
      if (!Files.exists(base)) {
        continue;
      }
      try (var paths = Files.walk(base)) {
        for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
          snapshot.put(root.relativize(path).toString(), ByteBuffer.wrap(Files.readAllBytes(path)));
        }
      }
    }
    return snapshot;
  }

  private static List<Path> temporarySiblings(Path root) throws Exception {
    try (var paths = Files.list(root.getParent())) {
      return paths
          .filter(path -> path.getFileName().toString().startsWith("." + root.getFileName() + ".astro-export-"))
          .sorted()
          .toList();
    }
  }
}
