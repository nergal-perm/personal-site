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
import dev.eugene.astroexport.review.PublishedSnapshotStore;
import dev.eugene.astroexport.review.ReviewWorkspace;
import dev.eugene.astroexport.testsupport.CommandFixture;
import dev.eugene.astroexport.workflow.WorkflowStateService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
    Path publishedRu = review.resolve("blog/essay/published/ru.md");
    Path publishedEn = review.resolve("blog/essay/published/en.md");
    assertEquals(
        ReviewWorkspace.renderRuReview(currentBlogEntry(vault)),
        Files.readString(publishedRu));
    assertEquals(reviewed, Files.readString(publishedEn));
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
    assertEquals("stale", payload.get("pairFreshness"));
    assertEquals(null, payload.get("translationStatus"));
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
  void snapshotFailureReturnsReadyToPublishAndRetryCompletesTheBaseline()
      throws Exception {
    Path vault = temp.resolve("vault");
    Path source = writeBlogNote(vault);
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    ReviewWorkspace.writePublishedSnapshot(
        review, "blog", "essay", "old ru\n", "old en\n");
    AtomicInteger stages = new AtomicInteger();
    CommandServices services = CommandServices.defaults()
        .withStageApprovedSnapshotAction((root, current, english) -> {
          ReviewWorkspace.PendingPublishedSnapshot real =
              ReviewWorkspace.stageApprovedSnapshot(root, current, english);
          if (stages.incrementAndGet() > 1) return real;
          return failingCommit(real, new IllegalStateException("disk full"));
        });

    CommandFixture.Result failed = runMarkReviewed(
        new AstroExportCommand(services), vault, review, temp.resolve("jobs"));
    Map<String, Object> failedPayload = json(failed.stdout());
    assertEquals(1, failed.exitCode());
    assertEquals(false, failedPayload.get("ok"));
    assertEquals("ready_to_publish", failedPayload.get("status"));
    assertEquals("published-snapshot", firstDiagnosticField(failedPayload));
    assertEquals("old ru\n",
        Files.readString(review.resolve("blog/essay/published/ru.md")));
    assertTrue(Files.readString(source)
        .contains("publicWorkflowStatus: \"ready_to_publish\""));

    CommandFixture.Result retried = runMarkReviewed(
        new AstroExportCommand(services), vault, review, temp.resolve("jobs"));
    assertEquals(0, retried.exitCode(), retried.stderr());
    assertEquals(
        ReviewWorkspace.renderRuReview(currentBlogEntry(vault)),
        Files.readString(review.resolve("blog/essay/published/ru.md")));
  }

  @Test
  void incompleteSnapshotRollbackReturnsBlockingRecoveryOutcome()
      throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault);
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    ReviewWorkspace.writePublishedSnapshot(
        review, "blog", "essay", "old ru\n", "old en\n");
    Path published = review.resolve("blog/essay/published");
    Path recovery = review.resolve("blog/essay/.published-stage-recovery");
    Files.createDirectories(recovery);
    Files.writeString(recovery.resolve("ru.md"), "old ru\n");
    Files.writeString(recovery.resolve("en.md"), "old en\n");
    CommandServices services = CommandServices.defaults()
        .withStageApprovedSnapshotAction((root, current, english) -> {
          ReviewWorkspace.PendingPublishedSnapshot real =
              ReviewWorkspace.stageApprovedSnapshot(root, current, english);
          return new ReviewWorkspace.PendingPublishedSnapshot() {
            @Override
            public ReviewWorkspace.PublishedSnapshotResult commit(
                List<WorkflowStateService.SnapshotGuard> guards) {
              real.commit(guards);
              throw new PublishedSnapshotStore.PublishedSnapshotRecoveryException(
                  "rollback incomplete",
                  PublishedSnapshotStore.RecoveryDisposition.CANDIDATE_VISIBLE,
                  published,
                  List.of(recovery),
                  new IOException("rollback failed"));
            }

            @Override
            public void close() {
              real.close();
            }
          };
        });

    CommandFixture.Result result = runMarkReviewed(
        new AstroExportCommand(services), vault, review, temp.resolve("jobs"));
    Map<String, Object> payload = json(result.stdout());
    Map<String, Object> diagnostic = firstDiagnostic(payload);

    assertEquals(1, result.exitCode());
    assertEquals(false, payload.get("ok"));
    assertEquals("recovery_required", payload.get("status"));
    assertEquals("published-snapshot-recovery", diagnostic.get("field"));
    assertEquals(true, diagnostic.get("blocking"));
    assertTrue(String.valueOf(diagnostic.get("message")).contains(published.toString()));
    assertTrue(String.valueOf(diagnostic.get("message")).contains(recovery.toString()));
    assertFalse(String.valueOf(diagnostic.get("message")).contains("invoke Mark"));
    assertFalse("old ru\n".equals(
        Files.readString(review.resolve("blog/essay/published/ru.md"))));
  }

  @Test
  void pendingCleanupFailureOverridesRetryAndReturnsBlockingRecoveryOutcome()
      throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault);
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    ReviewWorkspace.writePublishedSnapshot(
        review, "blog", "essay", "old ru\n", "old en\n");
    Path published = review.resolve("blog/essay/published");
    Path recovery = review.resolve("blog/essay/.published-stage-recovery");
    Files.createDirectories(recovery);
    Files.writeString(recovery.resolve("ru.md"), "candidate ru\n");
    Files.writeString(recovery.resolve("en.md"), "candidate en\n");
    CommandServices services = CommandServices.defaults()
        .withStageApprovedSnapshotAction((root, current, english) -> {
          ReviewWorkspace.PendingPublishedSnapshot real =
              ReviewWorkspace.stageApprovedSnapshot(root, current, english);
          return new ReviewWorkspace.PendingPublishedSnapshot() {
            @Override
            public ReviewWorkspace.PublishedSnapshotResult commit(
                List<WorkflowStateService.SnapshotGuard> guards) {
              throw new IllegalStateException("disk full");
            }

            @Override
            public void close() {
              real.close();
              throw new PublishedSnapshotStore.PublishedSnapshotRecoveryException(
                  "cleanup failed",
                  PublishedSnapshotStore.RecoveryDisposition.STAGED_CANDIDATE,
                  published,
                  List.of(recovery),
                  new IOException("cleanup failed"));
            }
          };
        });

    CommandFixture.Result result = runMarkReviewed(
        new AstroExportCommand(services), vault, review, temp.resolve("jobs"));
    Map<String, Object> payload = json(result.stdout());
    Map<String, Object> diagnostic = firstDiagnostic(payload);

    assertEquals(1, result.exitCode());
    assertEquals(false, payload.get("ok"));
    assertEquals("recovery_required", payload.get("status"));
    assertEquals("published-snapshot-recovery", diagnostic.get("field"));
    assertEquals(true, diagnostic.get("blocking"));
    assertTrue(String.valueOf(diagnostic.get("message")).contains(recovery.toString()));
    assertFalse(String.valueOf(diagnostic.get("message")).contains("invoke Mark"));
  }

  @Test
  void suppressedPendingCleanupFailureOverridesFinalPreflightFailure()
      throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault);
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    Path published = review.resolve("blog/essay/published");
    Path recovery = review.resolve("blog/essay/.published-stage-recovery");
    Files.createDirectories(recovery);
    Files.writeString(recovery.resolve("ru.md"), "candidate ru\n");
    Files.writeString(recovery.resolve("en.md"), "candidate en\n");
    AtomicInteger preflights = new AtomicInteger();
    CommandServices services = CommandServices.defaults()
        .withStageApprovedSnapshotAction((root, current, english) -> {
          ReviewWorkspace.PendingPublishedSnapshot real =
              ReviewWorkspace.stageApprovedSnapshot(root, current, english);
          return new ReviewWorkspace.PendingPublishedSnapshot() {
            @Override
            public ReviewWorkspace.PublishedSnapshotResult commit(
                List<WorkflowStateService.SnapshotGuard> guards) {
              return real.commit(guards);
            }

            @Override
            public void close() {
              real.close();
              throw new PublishedSnapshotStore.PublishedSnapshotRecoveryException(
                  "cleanup failed",
                  PublishedSnapshotStore.RecoveryDisposition.STAGED_CANDIDATE,
                  published,
                  List.of(recovery),
                  new IOException("cleanup failed"));
            }
          };
        })
        .withPreflightObserver((actualVault, note) -> {
          if (preflights.incrementAndGet() == 5) {
            throw new IOException("final preflight failed");
          }
        });

    CommandFixture.Result result = runMarkReviewed(
        new AstroExportCommand(services), vault, review, temp.resolve("jobs"));
    Map<String, Object> payload = json(result.stdout());
    Map<String, Object> diagnostic = firstDiagnostic(payload);

    assertEquals(1, result.exitCode());
    assertEquals(false, payload.get("ok"));
    assertEquals("recovery_required", payload.get("status"));
    assertEquals("published-snapshot-recovery", diagnostic.get("field"));
    assertEquals(true, diagnostic.get("blocking"));
    assertTrue(String.valueOf(diagnostic.get("message")).contains(recovery.toString()));
  }

  @Test
  void suppressedPendingCleanupFailureOverridesUnexpectedRuntime()
      throws Exception {
    Path vault = temp.resolve("vault");
    Path source = writeBlogNote(vault);
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    Path published = review.resolve("blog/essay/published");
    Path recovery = review.resolve("blog/essay/.published-stage-recovery");
    Files.createDirectories(recovery);
    Files.writeString(recovery.resolve("ru.md"), "candidate ru\n");
    Files.writeString(recovery.resolve("en.md"), "candidate en\n");
    CommandServices services = CommandServices.defaults()
        .withStageApprovedSnapshotAction((root, current, english) -> {
          ReviewWorkspace.PendingPublishedSnapshot real =
              ReviewWorkspace.stageApprovedSnapshot(root, current, english);
          return new ReviewWorkspace.PendingPublishedSnapshot() {
            @Override
            public ReviewWorkspace.PublishedSnapshotResult commit(
                List<WorkflowStateService.SnapshotGuard> guards) {
              return real.commit(guards);
            }

            @Override
            public void close() {
              real.close();
              throw new PublishedSnapshotStore.PublishedSnapshotRecoveryException(
                  "cleanup failed",
                  PublishedSnapshotStore.RecoveryDisposition.STAGED_CANDIDATE,
                  published,
                  List.of(recovery),
                  new IOException("cleanup failed"));
            }
          };
        })
        .withPreflightObserver((actualVault, note) -> {
          if (Files.readString(source).contains(
              "publicWorkflowStatus: \"ready_to_publish\"")) {
            throw new IllegalStateException(
                "unexpected final search failure");
          }
        });

    CommandFixture.Result result = runMarkReviewed(
        new AstroExportCommand(services), vault, review, temp.resolve("jobs"));
    Map<String, Object> payload = json(result.stdout());
    Map<String, Object> diagnostic = firstDiagnostic(payload);

    assertEquals(1, result.exitCode());
    assertEquals(false, payload.get("ok"));
    assertEquals("recovery_required", payload.get("status"), result.stdout());
    assertEquals("published-snapshot-recovery", diagnostic.get("field"));
    assertEquals(true, diagnostic.get("blocking"));
    assertTrue(String.valueOf(diagnostic.get("message")).contains(recovery.toString()));
  }

  @Test
  void unexpectedRuntimeWithoutRecoveryStillPropagates()
      throws Exception {
    Path vault = temp.resolve("vault");
    Path source = writeBlogNote(vault);
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    CommandServices services = CommandServices.defaults()
        .withPreflightObserver((actualVault, note) -> {
          if (Files.readString(source).contains(
              "publicWorkflowStatus: \"ready_to_publish\"")) {
            throw new IllegalStateException(
                "unexpected final search failure");
          }
        });

    IllegalStateException error = assertThrows(
        IllegalStateException.class,
        () -> runMarkReviewed(
            new AstroExportCommand(services),
            vault,
            review,
            temp.resolve("jobs")));

    assertEquals("unexpected final search failure", error.getMessage());
  }

  @Test
  void alreadyReviewedRetryUsesValidatedBytesWithoutRewriteParser()
      throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault);
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    Path english = review.resolve("blog/essay/en.md");
    Files.createDirectories(english.getParent());
    String reviewed = """
        ---
        sourceHash: %s
        title: &status reviewed
        translationStatus: *status
        translatedAt: 2026-07-17
        translationProfile: codex-test-v1
        description: English description.
        ---
        English body.
        """.formatted(entry.translationSourceHash());
    Files.writeString(english, reviewed);

    CommandFixture.Result result = runMarkReviewed(
        new AstroExportCommand(CommandServices.defaults()),
        vault,
        review,
        temp.resolve("jobs"));
    Map<String, Object> payload = json(result.stdout());

    assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
    assertEquals(true, payload.get("ok"));
    assertEquals("ready_to_publish", payload.get("status"));
    assertEquals(reviewed, Files.readString(english));
    assertEquals(
        reviewed,
        Files.readString(review.resolve("blog/essay/published/en.md")));
  }

  @Test
  void sourceChangeAtPublishedCommitReturnsStaleAndPreservesOldPair()
      throws Exception {
    Path vault = temp.resolve("vault");
    Path source = writeBlogNote(vault);
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    ReviewWorkspace.writePublishedSnapshot(
        review, "blog", "essay", "old ru\n", "old en\n");
    CommandServices services = CommandServices.defaults()
        .withStageApprovedSnapshotAction((root, current, english) -> {
          ReviewWorkspace.PendingPublishedSnapshot real =
              ReviewWorkspace.stageApprovedSnapshot(root, current, english);
          return new ReviewWorkspace.PendingPublishedSnapshot() {
            @Override
            public ReviewWorkspace.PublishedSnapshotResult commit(
                List<WorkflowStateService.SnapshotGuard> guards) {
              try {
                Files.writeString(
                    source,
                    Files.readString(source)
                        .replace("Text.", "Changed at snapshot boundary."));
              } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
              }
              return real.commit(guards);
            }

            @Override
            public void close() {
              real.close();
            }
          };
        });

    CommandFixture.Result result = runMarkReviewed(
        new AstroExportCommand(services), vault, review, temp.resolve("jobs"));
    Map<String, Object> payload = json(result.stdout());

    assertEquals(1, result.exitCode());
    assertEquals(false, payload.get("ok"));
    assertEquals("stale", payload.get("status"));
    assertEquals("stale", payload.get("pairFreshness"));
    assertEquals(null, payload.get("translationStatus"));
    assertEquals("old ru\n",
        Files.readString(review.resolve("blog/essay/published/ru.md")));
    assertEquals("old en\n",
        Files.readString(review.resolve("blog/essay/published/en.md")));
  }

  @Test
  void guardConflictOmitsPairStateWhenFreshPreflightCannotBeEstablished()
      throws Exception {
    Path vault = temp.resolve("vault");
    Path source = writeBlogNote(vault);
    ManifestEntry entry = currentBlogEntry(vault);
    Path review = temp.resolve("review");
    Path en = writeBlogReviewEn(
        review, entry.translationSourceHash(), "generated");
    byte[] enBefore = Files.readAllBytes(en);
    CommandServices defaults = CommandServices.defaults();
    CommandServices services = defaults.withReplaceEnglishReviewAction(
        (actualReview, content, collection, publicId, expected, guards) -> {
          Files.delete(source);
          return defaults.replaceEnglishReview(
              actualReview,
              content,
              collection,
              publicId,
              expected,
              guards);
        });

    CommandFixture.Result result = runMarkReviewed(
        new AstroExportCommand(services), vault, review, temp.resolve("jobs"));
    Map<String, Object> payload = json(result.stdout());

    assertEquals(1, result.exitCode());
    assertEquals(false, payload.get("ok"));
    assertEquals("stale", payload.get("status"));
    assertEquals(null, payload.get("pairFreshness"));
    assertEquals(null, payload.get("translationStatus"));
    assertEquals(ByteBuffer.wrap(enBefore), ByteBuffer.wrap(Files.readAllBytes(en)));
  }

  @Test
  void invariantRuProjectionChangeAfterStagingReturnsStaleAndPreservesOldPair()
      throws Exception {
    Path vault = temp.resolve("vault");
    Path source = writeBlogNote(vault);
    Files.writeString(source, Files.readString(source)
        .replace(
            "publish: true",
            """
            publish: true
            topics: [systems]
            publicWorkflowStatus: "ready_to_publish"
            publicTranslationStatus: "reviewed"
            """));
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "reviewed");
    ReviewWorkspace.writePublishedSnapshot(
        review, "blog", "essay", "old ru\n", "old en\n");
    Clock mutatingClock = new Clock() {
      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        try {
          Files.writeString(
              source,
              Files.readString(source).replace(
                  "topics: [systems]", "topics: [software]"));
        } catch (IOException error) {
          throw new java.io.UncheckedIOException(error);
        }
        return Instant.parse("2026-07-28T00:00:00Z");
      }
    };

    CommandFixture.Result result = runMarkReviewed(
        new AstroExportCommand(
            CommandServices.defaults().withClock(mutatingClock)),
        vault,
        review,
        temp.resolve("jobs"));
    Map<String, Object> payload = json(result.stdout());

    assertEquals(1, result.exitCode());
    assertEquals(false, payload.get("ok"));
    assertEquals("stale", payload.get("status"));
    assertEquals("old ru\n",
        Files.readString(review.resolve("blog/essay/published/ru.md")));
    assertEquals("old en\n",
        Files.readString(review.resolve("blog/essay/published/en.md")));
    assertTrue(Files.readString(source).contains("topics: [software]"));
  }

  @Test
  void metadataBlockedFinalPreflightReturnsStaleAndPreservesOldPair()
      throws Exception {
    Path vault = temp.resolve("vault");
    Path source = writeBlogNote(vault);
    Files.writeString(source, Files.readString(source)
        .replace(
            "publish: true",
            """
            publish: true
            publicWorkflowStatus: "ready_to_publish"
            publicTranslationStatus: "reviewed"
            """));
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "reviewed");
    ReviewWorkspace.writePublishedSnapshot(
        review, "blog", "essay", "old ru\n", "old en\n");
    Clock mutatingClock = new Clock() {
      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        try {
          Files.writeString(
              source,
              Files.readString(source).replace(
                  "publicId: essay", "publicId:"));
        } catch (IOException error) {
          throw new java.io.UncheckedIOException(error);
        }
        return Instant.parse("2026-07-28T00:00:00Z");
      }
    };

    CommandFixture.Result result = runMarkReviewed(
        new AstroExportCommand(
            CommandServices.defaults().withClock(mutatingClock)),
        vault,
        review,
        temp.resolve("jobs"));
    Map<String, Object> payload = json(result.stdout());

    assertEquals(1, result.exitCode());
    assertEquals(false, payload.get("ok"));
    assertEquals("stale", payload.get("status"));
    assertEquals(null, payload.get("pairFreshness"));
    assertEquals(null, payload.get("translationStatus"));
    assertEquals("old ru\n",
        Files.readString(review.resolve("blog/essay/published/ru.md")));
    assertEquals("old en\n",
        Files.readString(review.resolve("blog/essay/published/en.md")));
    assertTrue(Files.readString(source).contains("\npublicId:\n"));
  }

  @Test
  void finalPreflightFailureAfterSourceApprovalReturnsReadyToPublish()
      throws Exception {
    Path vault = temp.resolve("vault");
    Path source = writeBlogNote(vault);
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    Path english = writeBlogReviewEn(
        review, entry.translationSourceHash(), "generated");
    ReviewWorkspace.writePublishedSnapshot(
        review, "blog", "essay", "old ru\n", "old en\n");
    AtomicInteger preflights = new AtomicInteger();
    CommandServices services = CommandServices.defaults()
        .withPreflightObserver((actualVault, note) -> {
          if (preflights.incrementAndGet() == 5) {
            throw new IOException("final preflight failed");
          }
        });

    CommandFixture.Result result = runMarkReviewed(
        new AstroExportCommand(services), vault, review, temp.resolve("jobs"));
    Map<String, Object> payload = json(result.stdout());
    Map<String, Object> diagnostic = firstDiagnostic(payload);

    assertEquals(1, result.exitCode());
    assertEquals(false, payload.get("ok"));
    assertEquals("ready_to_publish", payload.get("status"));
    assertEquals("reviewed", payload.get("translationStatus"));
    assertEquals("published-snapshot", diagnostic.get("field"));
    assertTrue(String.valueOf(diagnostic.get("message"))
        .contains("invoke Mark current translation reviewed again"));
    assertTrue(Files.readString(source)
        .contains("publicWorkflowStatus: \"ready_to_publish\""));
    assertTrue(Files.readString(english)
        .contains("translationStatus: \"reviewed\""));
    assertEquals("old ru\n",
        Files.readString(review.resolve("blog/essay/published/ru.md")));
    assertEquals("old en\n",
        Files.readString(review.resolve("blog/essay/published/en.md")));
  }

  @Test
  void buildFromReviewDoesNotCreateOrReplaceApprovedSnapshot() throws Exception {
    Path vault = temp.resolve("vault");
    writeBlogNote(vault, "Exported body.");
    Path review = temp.resolve("review");
    ManifestEntry entry = currentBlogEntry(vault);
    writeBlogReviewEn(review, entry.translationSourceHash(), "generated");
    ReviewWorkspace.writePublishedSnapshot(
        review, "blog", "essay", "approved ru\n", "approved en\n");
    Path out = writeAstroRoot(temp.resolve("astro"));

    CommandFixture.Result result = run(
        new AstroExportCommand(CommandServices.defaults()
            .withGateRunner(invocation ->
                new SiteWriter.GateResult(0, "gate ok\n", ""))),
        "build-from-review",
        "--vault", vault.toString(),
        "--out", out.toString(),
        "--report", temp.resolve("report.md").toString(),
        "--review", review.toString());

    assertEquals(0, result.exitCode(), result.stderr());
    assertEquals(
        "approved ru\n",
        Files.readString(review.resolve("blog/essay/published/ru.md")));
    assertEquals(
        "approved en\n",
        Files.readString(review.resolve("blog/essay/published/en.md")));
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

  private static ReviewWorkspace.PendingPublishedSnapshot failingCommit(
      ReviewWorkspace.PendingPublishedSnapshot delegate,
      RuntimeException failure) {
    return new ReviewWorkspace.PendingPublishedSnapshot() {
      @Override
      public ReviewWorkspace.PublishedSnapshotResult commit(
          List<WorkflowStateService.SnapshotGuard> guards) {
        throw failure;
      }

      @Override
      public void close() {
        delegate.close();
      }
    };
  }

  private static CommandFixture.Result runMarkReviewed(
      AstroExportCommand command,
      Path vault,
      Path review,
      Path jobs) {
    return run(
        command,
        "mark-reviewed",
        "--vault", vault.toString(),
        "--note", "anywhere/Essay.md",
        "--review", review.toString(),
        "--jobs", jobs.toString(),
        "--json");
  }

  private static String firstDiagnosticField(Map<String, Object> payload) {
    return String.valueOf(firstDiagnostic(payload).get("field"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> firstDiagnostic(Map<String, Object> payload) {
    List<Map<String, Object>> diagnostics =
        (List<Map<String, Object>>) payload.get("diagnostics");
    return diagnostics.getFirst();
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
