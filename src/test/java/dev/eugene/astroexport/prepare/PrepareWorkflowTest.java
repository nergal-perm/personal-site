package dev.eugene.astroexport.prepare;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
import dev.eugene.astroexport.process.CodexRunner;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class PrepareWorkflowTest {
  private static final Instant NOW = Instant.parse("2026-07-18T12:30:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir
  Path temp;

  @Test
  void createsValidGeneratedDraftInBoundedJobWithoutAstroWrites() throws Exception {
    Fixture fixture = fixture();
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
    assertTrue(result.diagnostics().isEmpty());
    assertNotNull(result.jobId());
    Path job = fixture.jobs().resolve("blog/essay").resolve(result.jobId());
    assertEquals(job.toRealPath(), runner.workdir.toRealPath());
    assertEquals(
        List.of("agent-message.txt", "candidate.en.md", "instructions.md", "job.json", "ru.md"),
        Files.list(job).map(path -> path.getFileName().toString()).sorted().toList());
    assertFalse(Files.readString(job.resolve("ru.md")).contains("targetPath:"));
    assertTrue(runner.prompt.contains("candidate.en.md"));
    assertFalse(runner.prompt.contains(fixture.vault().toString()));
    assertFalse(runner.prompt.contains(fixture.review().toString()));
    String durable = Files.readString(fixture.review().resolve("blog/essay/en.md"));
    assertEquals(
        "generated",
        FrontmatterDocument.parse(Path.of("en.md"), "en.md", durable)
            .metadata().get("translationStatus"));
    assertTrue(Files.readString(fixture.source()).contains(
        "publicWorkflowStatus: \"ready_for_review\""));
    assertFalse(Files.exists(temp.resolve("astro")));
    Map<String, Object> journal = journal(fixture, result);
    assertEquals("succeeded", journal.get("state"));
    assertEquals(
        List.of("created", "running", "running", "succeeded"),
        ((List<Map<String, Object>>) journal.get("history")).stream()
            .map(event -> event.get("state")).toList());
  }

  @Test
  void runnerFailurePreservesPriorEnglishAndRecordsFailure() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] before = Files.readAllBytes(prior);
    RecordingRunner runner = new RecordingRunner(
        job -> new CodexRunner.Run(19, "ignored", "private stderr", false));

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertArrayEquals(before, Files.readAllBytes(prior));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains("exit code 19")));
    assertEquals("failed", journal(fixture, result).get("state"));
  }

  @Test
  void marksStaleWhenSourceChangesDuringJob() throws Exception {
    Fixture fixture = fixture();
    RecordingRunner runner = new RecordingRunner(job -> {
      Files.writeString(
          fixture.source(),
          Files.readString(fixture.source()).replace("Russian body.", "Changed body."));
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("stale", result.status());
    assertFalse(Files.exists(fixture.review().resolve("blog/essay/en.md")));
    assertEquals("stale", journal(fixture, result).get("state"));
  }

  @Test
  void onlyOneAgentRunsPerPublication() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    RecordingRunner runner = new RecordingRunner(job -> {
      calls.incrementAndGet();
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });
    PrepareWorkflow workflow = workflow(runner);
    PrepareWorkflow.PrepareResult[] first = new PrepareWorkflow.PrepareResult[1];
    Thread thread = new Thread(() ->
        first[0] = workflow.prepare(
            fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs()));
    thread.start();
    assertTrue(entered.await(5, TimeUnit.SECONDS));

    PrepareWorkflow.PrepareResult second = workflow.prepare(
        fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());
    release.countDown();
    thread.join();

    assertEquals("translating", second.status());
    assertEquals(1, calls.get());
    assertEquals("ready_for_review", first[0].status());
  }

  @Test
  void rejectsJobsSymlinkEscapeBeforeRunningAgent() throws Exception {
    Fixture fixture = fixture();
    Path outside = temp.resolve("outside");
    Files.createDirectory(outside);
    Files.createDirectories(fixture.jobs());
    Files.createSymbolicLink(fixture.jobs().resolve("blog"), outside);
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertTrue(result.diagnostics().stream().anyMatch(item -> item.message().contains("escapes")));
    assertEquals(0, runner.calls.get());
    assertEquals(List.of(), Files.list(outside).toList());
  }

  @Test
  void rejectsCandidateSymlinkEscapeAndPreservesPriorEnglish() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] before = Files.readAllBytes(prior);
    Path outside = temp.resolve("outside.en.md");
    Files.writeString(outside, "outside");
    RecordingRunner runner = new RecordingRunner(job -> {
      Files.createSymbolicLink(job.resolve("candidate.en.md"), outside);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertArrayEquals(before, Files.readAllBytes(prior));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains("regular file")));
  }

  @Test
  void rejectsPostRunInputTamperAndPreservesPriorEnglish() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] before = Files.readAllBytes(prior);
    RecordingRunner runner = new RecordingRunner(job -> {
      Files.writeString(job.resolve("ru.md"), "tampered");
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertArrayEquals(before, Files.readAllBytes(prior));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains("ru.md changed")));
  }

  @Test
  void metadataBlockerUpdatesSourceWithoutCreatingJob() throws Exception {
    Fixture fixture = fixture();
    Files.writeString(
        fixture.source(),
        Files.readString(fixture.source()).replace("publicContentType: essay",
            "publicContentType: case"));
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("metadata_blocked", result.status());
    assertNull(result.entry());
    assertEquals(0, runner.calls.get());
    assertFalse(Files.exists(fixture.jobs()));
    assertTrue(Files.readString(fixture.source()).contains(
        "publicWorkflowStatus: \"metadata_blocked\""));
  }

  @Test
  void missingCandidatePreservesPriorEnglishAndMarksFailed() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] before = Files.readAllBytes(prior);
    RecordingRunner runner = new RecordingRunner(
        job -> new CodexRunner.Run(0, "", "", false));

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertArrayEquals(before, Files.readAllBytes(prior));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains("candidate.en.md")));
    assertEquals("failed", journal(fixture, result).get("state"));
  }

  @Test
  void invalidCandidateHashPreservesPriorEnglishAndMarksFailed() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] before = Files.readAllBytes(prior);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, "f".repeat(64));
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertArrayEquals(before, Files.readAllBytes(prior));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().toLowerCase().contains("sourcehash does not match")));
  }

  @Test
  void candidateInternalRussianRouteIsRejected() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] before = Files.readAllBytes(prior);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      Files.writeString(
          job.resolve("candidate.en.md"),
          Files.readString(job.resolve("candidate.en.md"))
              .replace("Fresh English body.", "Read [peer](/ru/notes/peer/)."));
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertArrayEquals(before, Files.readAllBytes(prior));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains("internal /ru/ route")));
  }

  @Test
  void reviewSymlinkSwapAfterRunnerIsRejected() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] before = Files.readAllBytes(prior);
    Path reviewDirectory = prior.getParent();
    Path saved = reviewDirectory.resolveSibling("essay.saved");
    Path escaped = temp.resolve("escaped-review");
    Files.createDirectory(escaped);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      Files.move(reviewDirectory, saved);
      Files.createSymbolicLink(reviewDirectory, escaped);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertArrayEquals(before, Files.readAllBytes(saved.resolve("en.md")));
    assertEquals(List.of(), Files.list(escaped).toList());
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains("review root")));
  }

  @Test
  void priorEnglishIsSanitizedBeforeEnteringJob() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    Files.writeString(
        prior,
        Files.readString(prior)
            .replaceFirst("---\\n", "---\nlocalPath: secret.md\nreviewPath: /srv/review/en.md\n")
            .replace("Prior English body.",
                "Prior context /srv/private/Plan.md and C:\\\\private\\\\Plan.md."));
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
    String jobPrior = Files.readString(runner.workdir.resolve("en.md"));
    assertFalse(jobPrior.contains("localPath"));
    assertFalse(jobPrior.contains("reviewPath"));
    assertFalse(jobPrior.contains("secret.md"));
    assertFalse(jobPrior.contains("/srv/private"));
    assertTrue(jobPrior.contains("[publication path removed]"));
  }

  @Test
  void unparseablePriorEnglishIsOmittedFromJob() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    Files.writeString(prior, "---\nlocalPath: secret.md\nbad: [\n---\nPrior.\n");
    RecordingRunner runner = new RecordingRunner(job -> {
      assertFalse(Files.exists(job.resolve("en.md")));
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
  }

  @Test
  void pathlikeSourceIsRejectedBeforeReviewAndJobArtifacts() throws Exception {
    Fixture fixture = fixture();
    Files.writeString(
        fixture.source(),
        Files.readString(fixture.source())
            .replace("Russian body.", "Local path /srv/private/Plan.md."));
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    assertFalse(Files.exists(fixture.review().resolve("blog/essay/ru.md")));
    assertFalse(Files.exists(fixture.jobs().resolve("blog/essay")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"C:/", "~/"})
  void exactLocalRootsAreRejectedBeforeReviewAndJobArtifacts(String pathValue)
      throws Exception {
    Fixture fixture = fixture();
    Files.writeString(
        fixture.source(),
        Files.readString(fixture.source())
            .replace("Russian body.", "Local root " + pathValue));
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    assertFalse(Files.exists(fixture.review().resolve("blog/essay/ru.md")));
    assertFalse(Files.exists(fixture.jobs().resolve("blog/essay")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"notes/Plan.md", "config/local.json"})
  void genericRelativeFilePathsAreRejectedBeforeArtifacts(String pathValue)
      throws Exception {
    Fixture fixture = fixture();
    Files.writeString(
        fixture.source(),
        Files.readString(fixture.source())
            .replace("Russian description.", "Local file " + pathValue));
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    assertFalse(Files.exists(fixture.review().resolve("blog/essay/ru.md")));
    assertFalse(Files.exists(fixture.jobs().resolve("blog/essay")));
  }

  @Test
  void publicRoutesUrlsAndSlashProseAreAllowed() throws Exception {
    Fixture fixture = fixture();
    Files.writeString(
        fixture.source(),
        Files.readString(fixture.source()).replace(
            "Russian body.",
            "A/B and https://example.com/file.md with /ru/essays/essay/ and /en/essays/essay/."));
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
  }

  @Test
  void existingEnglishHardlinkIsRejectedBeforeRunner() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    Path external = temp.resolve("external-en.md");
    Files.move(prior, external, StandardCopyOption.REPLACE_EXISTING);
    Files.createLink(prior, external);
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains("multiple hard links")));
    assertFalse(Files.exists(fixture.review().resolve("blog/essay/ru.md")));
  }

  @Test
  void existingEnglishPermissionsArePreservedAcrossReplacement() throws Exception {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    var expectedPermissions = PosixFilePermissions.fromString("rw-r-----");
    Files.setPosixFilePermissions(prior, expectedPermissions);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
    assertEquals(expectedPermissions, Files.getPosixFilePermissions(prior));
  }

  @ParameterizedTest
  @ValueSource(strings = {"symlink", "hardlink"})
  void sameContentEnglishLeafSubstitutionDuringJobIsRejected(String substitution)
      throws Exception {
    assumeTrue(
        !"hardlink".equals(substitution)
            || FileSystems.getDefault().supportedFileAttributeViews().contains("unix"));
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] before = Files.readAllBytes(prior);
    Path external = temp.resolve("external-en.md");
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      Files.move(prior, external);
      if ("symlink".equals(substitution)) {
        Files.createSymbolicLink(prior, external);
      } else {
        Files.createLink(prior, external);
      }
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertArrayEquals(before, Files.readAllBytes(external));
    assertArrayEquals(before, Files.readAllBytes(prior));
    assertTrue(result.diagnostics().stream().anyMatch(item ->
        item.message().contains(
            "symlink".equals(substitution) ? "symbolic link" : "multiple hard links")));
  }

  @Test
  void leafLockSymlinkIsRejectedWithoutRunningAgent() throws Exception {
    Fixture fixture = fixture();
    Path lockParent = fixture.jobs().resolve("blog");
    Files.createDirectories(lockParent);
    Path target = fixture.jobs().resolve("shared-lock-target");
    Files.writeString(target, "do not follow\n");
    Files.createSymbolicLink(lockParent.resolve("essay.lock"), target);
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    assertEquals("do not follow\n", Files.readString(target));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.field().equals("lock")));
  }

  private PrepareWorkflow workflow(RecordingRunner runner) {
    return new PrepareWorkflow(runner, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Fixture fixture() throws Exception {
    Path vault = temp.resolve("vault");
    Path source = vault.resolve("blog/Essay.md");
    Files.createDirectories(source.getParent());
    Files.writeString(source, """
        ---
        id: essay
        title: Russian title
        publish: true
        publicId: essay
        publicCollection: blog
        publicContentType: essay
        description: Russian description.
        topics:
          - systems
        ---
        Russian body.
        """);
    return new Fixture(vault, source, temp.resolve("review"), temp.resolve("jobs"));
  }

  private Path priorEnglish(Fixture fixture) throws Exception {
    Path path = fixture.review().resolve("blog/essay/en.md");
    Files.createDirectories(path.getParent());
    Files.writeString(path, """
        ---
        sourceHash: old
        translationStatus: reviewed
        translatedAt: 2026-07-01
        translationProfile: human-review-v1
        title: Prior English title
        description: Prior English description.
        ---
        Prior English body.
        """);
    return path;
  }

  private static void writeCandidate(Path job, String sourceHash) throws Exception {
    Map<String, Object> payload = JSON.readValue(
        Files.readString(job.resolve("job.json")), new TypeReference<>() { });
    String hash = sourceHash == null ? String.valueOf(payload.get("sourceHash")) : sourceHash;
    Files.writeString(job.resolve("candidate.en.md"), """
        ---
        sourceHash: %s
        translationStatus: reviewed
        translatedAt: 2026-07-18
        translationProfile: fake-codex-v1
        title: Fresh English title
        description: Fresh English description.
        ---
        Fresh English body.
        """.formatted(hash));
  }

  private static Map<String, Object> journal(
      Fixture fixture,
      PrepareWorkflow.PrepareResult result) throws Exception {
    return JSON.readValue(
        Files.readString(
            fixture.jobs().resolve("blog/essay").resolve(result.jobId()).resolve("job.json")),
        new TypeReference<>() { });
  }

  private record Fixture(Path vault, Path source, Path review, Path jobs) { }

  private static final class RecordingRunner implements PrepareWorkflow.TranslationRunner {
    private final Behavior behavior;
    private final AtomicInteger calls = new AtomicInteger();
    private Path workdir;
    private String prompt;

    private RecordingRunner(Behavior behavior) {
      this.behavior = behavior;
    }

    @Override
    public CodexRunner.Run run(Path workdir, String prompt, Duration timeout) throws Exception {
      calls.incrementAndGet();
      this.workdir = workdir;
      this.prompt = prompt;
      return behavior.run(workdir);
    }
  }

  @FunctionalInterface
  private interface Behavior {
    CodexRunner.Run run(Path job) throws Exception;
  }
}
