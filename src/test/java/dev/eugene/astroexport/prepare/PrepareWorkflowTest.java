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
import dev.eugene.astroexport.fs.AtomicExchange;
import dev.eugene.astroexport.fs.JnaAtomicExchange;
import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
import dev.eugene.astroexport.process.CodexRunner;
import dev.eugene.astroexport.review.ReviewWorkspace;
import dev.eugene.astroexport.validation.PublicationDiagnostic;
import dev.eugene.astroexport.workflow.WorkflowStateService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
  void promptIncludesUnifiedDiffOfRussianSourceWhenPublishedSnapshotDiffers() throws Exception {
    Fixture fixture = fixture();
    ReviewWorkspace.writePublishedSnapshot(
        fixture.review(), "blog", "essay",
        """
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
        Old Russian body.
        """,
        """
        ---
        sourceHash: old
        translationStatus: reviewed
        translatedAt: 2026-07-01
        translationProfile: human-review-v1
        title: Prior English title
        description: Prior English description.
        ---
        Old English body.
        """);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    workflow(runner).prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertTrue(runner.prompt.contains("<source-diff>"));
    assertTrue(runner.prompt.contains("-Old Russian body."));
    assertTrue(runner.prompt.contains("+Russian body."));
  }

  @Test
  void sourceDiffExcludesFrontmatterReserializationNoise() throws Exception {
    Fixture fixture = fixture();
    ReviewWorkspace.writePublishedSnapshot(
        fixture.review(), "blog", "essay",
        """
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
        Old Russian body.
        """,
        """
        ---
        sourceHash: old
        translationStatus: reviewed
        translatedAt: 2026-07-01
        translationProfile: human-review-v1
        title: Prior English title
        description: Prior English description.
        ---
        Old English body.
        """);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    workflow(runner).prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertTrue(runner.prompt.contains("<source-diff>"));
    assertFalse(runner.prompt.contains("-publicId: essay"));
  }

  @Test
  void flagsNonBlockingScopeDiagnosticWhenGeneratedTranslationChangesMoreThanTheRussianSource()
      throws Exception {
    Fixture fixture = fixture();
    Files.writeString(fixture.source(), """
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
        Paragraph one.

        Paragraph two edited.

        Paragraph three.
        """);
    ReviewWorkspace.writePublishedSnapshot(
        fixture.review(), "blog", "essay",
        """
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
        Paragraph one.

        Paragraph two.

        Paragraph three.
        """,
        """
        ---
        sourceHash: old
        translationStatus: reviewed
        translatedAt: 2026-07-01
        translationProfile: human-review-v1
        title: Russian title
        description: Russian description.
        ---
        English one.

        English two.

        English three.
        """);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null, "English one changed.\n\nEnglish two changed.\n\nEnglish three changed.\n");
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
    assertEquals(1, result.diagnostics().size());
    assertEquals("translation-scope", result.diagnostics().getFirst().field());
    assertFalse(result.diagnostics().getFirst().blocking());
  }

  @Test
  void omitsScopeDiagnosticWhenGeneratedChangeMatchesRussianScope() throws Exception {
    Fixture fixture = fixture();
    Files.writeString(fixture.source(), """
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
        Paragraph one.

        Paragraph two edited.

        Paragraph three.
        """);
    ReviewWorkspace.writePublishedSnapshot(
        fixture.review(), "blog", "essay",
        """
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
        Paragraph one.

        Paragraph two.

        Paragraph three.
        """,
        """
        ---
        sourceHash: old
        translationStatus: reviewed
        translatedAt: 2026-07-01
        translationProfile: human-review-v1
        title: Russian title
        description: Russian description.
        ---
        English one.

        English two.

        English three.
        """);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null, "English one.\n\nEnglish two changed.\n\nEnglish three.\n");
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
    assertTrue(result.diagnostics().isEmpty());
  }

  @Test
  void prepareDegradesGracefullyWhenPublishedRuSnapshotIsCorrupt() throws Exception {
    Fixture fixture = fixture();
    Path publishedRu = fixture.review().resolve("blog/essay/published/ru.md");
    Files.createDirectories(publishedRu.getParent());
    Files.writeString(publishedRu, """
        ---
        title: [Unclosed flow sequence
        ---
        Corrupt Russian body.
        """);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
    assertTrue(result.diagnostics().stream().noneMatch(PublicationDiagnostic::blocking));
  }

  @Test
  void prepareKeepsScopeDiagnosticNonBlockingWhenPublishedEnSnapshotIsCorrupt() throws Exception {
    Fixture fixture = fixture();
    Files.writeString(fixture.source(), """
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
        Paragraph one.

        Paragraph two edited.

        Paragraph three.
        """);
    Path publishedDirectory = fixture.review().resolve("blog/essay/published");
    Files.createDirectories(publishedDirectory);
    Files.writeString(publishedDirectory.resolve("ru.md"), """
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
        Paragraph one.

        Paragraph two.

        Paragraph three.
        """);
    Files.writeString(publishedDirectory.resolve("en.md"), """
        ---
        translationStatus: [Unclosed flow sequence
        ---
        Corrupt English body.
        """);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null, "English one changed.\n\nEnglish two changed.\n\nEnglish three changed.\n");
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
    assertTrue(result.diagnostics().stream().noneMatch(PublicationDiagnostic::blocking));
  }

  @Test
  void flagsScopeDiagnosticWhenOnlyFrontmatterChangedInSourceButEnglishBodyChangedSubstantially()
      throws Exception {
    Fixture fixture = fixture();
    Files.writeString(fixture.source(), """
        ---
        id: essay
        title: Russian title
        publish: true
        publicId: essay
        publicCollection: blog
        publicContentType: essay
        description: Updated Russian description.
        topics:
          - systems
        ---
        Paragraph one.

        Paragraph two.

        Paragraph three.
        """);
    ReviewWorkspace.writePublishedSnapshot(
        fixture.review(), "blog", "essay",
        """
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
        Paragraph one.

        Paragraph two.

        Paragraph three.
        """,
        """
        ---
        sourceHash: old
        translationStatus: reviewed
        translatedAt: 2026-07-01
        translationProfile: human-review-v1
        title: Russian title
        description: Russian description.
        ---
        English one.

        English two.

        English three.
        """);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null, "English one changed.\n\nEnglish two changed.\n\nEnglish three changed.\n");
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
    assertEquals(1, result.diagnostics().size());
    assertEquals("translation-scope", result.diagnostics().getFirst().field());
    assertFalse(result.diagnostics().getFirst().blocking());
  }

  @Test
  void promptOmitsDiffSectionWhenNoPublishedSnapshotExists() throws Exception {
    Fixture fixture = fixture();
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    workflow(runner).prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertFalse(runner.prompt.contains("<source-diff>"));
  }

  @Test
  void conceptCandidateTemplatePreservesDefinitionBodyAndDraftControls() throws Exception {
    Fixture fixture = conceptFixture();
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null, "## Definition\n\nEnglish definition.\n");
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(
            fixture.vault(),
            "concepts/Organisation.md",
            fixture.review(),
            fixture.jobs());

    assertEquals("ready_for_review", result.status());
    Path job = fixture.jobs().resolve("concepts/organisation").resolve(result.jobId());
    FrontmatterDocument normalizedRu = FrontmatterDocument.parse(
        job.resolve("ru.md"), "ru.md", Files.readString(job.resolve("ru.md")));
    assertFalse(normalizedRu.metadata().containsKey("definition"));
    assertTrue(normalizedRu.body().contains("## Определение\n\nРусское определение."));

    String template = runner.prompt
        .substring(
            runner.prompt.indexOf("<candidate-template>")
                + "<candidate-template>".length(),
            runner.prompt.indexOf("</candidate-template>"))
        .strip();
    FrontmatterDocument candidateTemplate = FrontmatterDocument.parse(
        Path.of("candidate.en.md"), "candidate.en.md", template);
    Map<String, Object> jobRecord = JSON.readValue(
        Files.readString(job.resolve("job.json")), new TypeReference<>() { });
    assertEquals("generated", candidateTemplate.metadata().get("translationStatus"));
    assertEquals("2026-07-18", candidateTemplate.metadata().get("translatedAt").toString());
    assertEquals("codex-agent-v1", candidateTemplate.metadata().get("translationProfile"));
    assertEquals(
        jobRecord.get("sourceHash"),
        candidateTemplate.metadata().get("sourceHash"));
    assertFalse(candidateTemplate.metadata().containsKey("publish"));
    assertTrue(candidateTemplate.body().contains(
        "## Определение\n\nРусское определение."));
    assertTrue(runner.prompt.contains(
        "complete\ntranslated body required by the template"));
  }

  @Test
  void pathBearingGeneratedCandidateTemplateIsRejectedBeforeJobCreation() throws Exception {
    Fixture fixture = fixture();
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });
    PrepareWorkflow.IoHooks ioHooks = new PrepareWorkflow.IoHooks() {
      @Override
      public String candidateTemplate(
          dev.eugene.astroexport.model.ManifestEntry entry,
          Instant now) {
        return """
            ---
            sourceHash: %s
            translationStatus: generated
            translatedAt: 2026-07-18
            translationProfile: codex-agent-v1
            title: Russian title
            ---
            Read /Users/private/notes.md before translating.
            """.formatted(entry.translationSourceHash());
      }
    };

    PrepareWorkflow.PrepareResult result = workflow(runner, new JnaAtomicExchange(), ioHooks)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    assertNull(result.jobId());
    assertFalse(Files.exists(fixture.jobs().resolve("blog/essay")));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.field().equals("input")
            && item.message().contains("filesystem path")));
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
  void sourceEditAfterCommittedPreflightCannotBecomeEnglishInstallGuard() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] previous = Files.readAllBytes(prior);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });
    AtomicInteger freshPreflights = new AtomicInteger();
    PrepareWorkflow.IoHooks ioHooks = new PrepareWorkflow.IoHooks() {
      @Override
      public void afterFreshPreflight(Path source) throws IOException {
        if (freshPreflights.incrementAndGet() == 3) {
          Files.writeString(
              source,
              Files.readString(source).replace(
                  "Russian body.", "Concurrent body after committed preflight."));
        }
      }
    };

    PrepareWorkflow.PrepareResult result = workflow(runner, new JnaAtomicExchange(), ioHooks)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("stale", result.status());
    assertEquals(3, freshPreflights.get());
    assertArrayEquals(previous, Files.readAllBytes(prior));
    assertTrue(Files.readString(fixture.source()).contains(
        "Concurrent body after committed preflight."));
    assertEquals("stale", journal(fixture, result).get("state"));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains(
            "changed while translation state was being validated")));
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
  void candidateValidationFailureScrubsTemporaryValidationPath() throws Exception {
    Fixture fixture = fixture();
    RecordingRunner runner = new RecordingRunner(job -> {
      Files.writeString(job.resolve("candidate.en.md"), """
          ---
          title: [unterminated
          ---
          English body.
          """);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    String diagnostic = result.diagnostics().stream()
        .map(item -> item.message())
        .reduce("", (left, right) -> left + "\n" + right);
    assertTrue(diagnostic.contains("candidate.en.md"), diagnostic);
    assertFalse(diagnostic.contains(".candidate-review-"), diagnostic);
    String source = Files.readString(fixture.source());
    assertTrue(source.contains("candidate.en.md"), source);
    assertFalse(source.contains(".candidate-review-"), source);
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
  void cleanPriorEnglishEntersJobByteForByte() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] exact = """
        ---
        # Preserve this translator note.
        title: 'Prior English title'
        description: "Prior: English description."
        translationProfile: human-review-v1
        translationStatus: reviewed
        translatedAt: 2026-07-01
        sourceHash: old
        ---
        Prior English body with intentional formatting.
        """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(prior, exact);
    byte[][] jobPrior = new byte[1][];
    RecordingRunner runner = new RecordingRunner(job -> {
      jobPrior[0] = Files.readAllBytes(job.resolve("en.md"));
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
    assertArrayEquals(exact, jobPrior[0]);
  }

  @Test
  void invalidUtf8PriorEnglishFailsBeforeJobCreation() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    Files.write(
        prior,
        new byte[] {(byte) 0xc3, (byte) 0x28},
        java.nio.file.StandardOpenOption.APPEND);
    byte[] invalidPrior = Files.readAllBytes(prior);
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    assertArrayEquals(invalidPrior, Files.readAllBytes(prior));
    assertFalse(Files.exists(fixture.jobs().resolve("blog/essay")));
    assertTrue(result.diagnostics().stream().anyMatch(item ->
        item.field().equals("review") && item.message().contains("UTF-8")));
  }

  @Test
  void pathBearingPriorEnglishMetadataKeysAreRedactedRecursively() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    Files.writeString(
        prior,
        Files.readString(prior).replaceFirst(
            "---\\n",
            "---\n\"notes/private.md\": top-level context\n"
                + "nested:\n  \"archive/private/Plan.md\": nested context\n"));
    RecordingRunner runner = new RecordingRunner(job -> {
      String jobPrior = Files.readString(job.resolve("en.md"));
      assertFalse(jobPrior.contains("notes/private.md"));
      assertFalse(jobPrior.contains("archive/private/Plan.md"));
      assertTrue(jobPrior.contains("[publication path removed]"));
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });

    PrepareWorkflow.PrepareResult result = workflow(runner)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
    assertEquals(1, runner.calls.get());
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
  void initialEnglishReadRejectsSymlinkSwapAfterLeafValidation() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] priorBytes = Files.readAllBytes(prior);
    Path saved = temp.resolve("saved-prior-en.md");
    Path external = temp.resolve("external-secret.md");
    Files.writeString(external, "external content must not enter the job\n");
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });
    PrepareWorkflow.ExistingEnglishReadHook swapAfterValidation = path -> {
      Files.move(path, saved);
      Files.createSymbolicLink(path, external);
    };
    PrepareWorkflow.RecoveryFilePreserver unusedPreserver = (temporary, target) -> {
      throw new AssertionError("recovery preservation must not run");
    };
    PrepareWorkflow workflow = new PrepareWorkflow(
        runner,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new WorkflowStateService(),
        new JnaAtomicExchange(),
        swapAfterValidation,
        unusedPreserver);

    PrepareWorkflow.PrepareResult result = workflow.prepare(
        fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    assertArrayEquals(priorBytes, Files.readAllBytes(saved));
    assertEquals("external content must not enter the job\n", Files.readString(external));
    assertFalse(Files.exists(fixture.jobs().resolve("blog/essay")));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().toLowerCase().contains("symbolic link")));
  }

  @Test
  void initialEnglishReadRejectsSameContentIdentitySwapBeforeOpen() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] priorBytes = Files.readAllBytes(prior);
    Path saved = temp.resolve("saved-prior-en.md");
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });
    PrepareWorkflow.ExistingEnglishReadHook replaceAfterValidation = path -> {
      Files.move(path, saved);
      Files.write(path, priorBytes);
    };
    PrepareWorkflow workflow = new PrepareWorkflow(
        runner,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new WorkflowStateService(),
        new JnaAtomicExchange(),
        replaceAfterValidation,
        (temporary, target) -> {
          throw new AssertionError("recovery preservation must not run");
        });

    PrepareWorkflow.PrepareResult result = workflow.prepare(
        fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    assertArrayEquals(priorBytes, Files.readAllBytes(saved));
    assertArrayEquals(priorBytes, Files.readAllBytes(prior));
    assertFalse(Files.exists(fixture.jobs().resolve("blog/essay")));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains("changed before it could be read")));
  }

  @Test
  void initialEnglishReadRejectsOpenedFileSwapBackBeforePathValidation() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] priorBytes = Files.readAllBytes(prior);
    Path savedOriginal = temp.resolve("saved-original-en.md");
    Path savedSubstitute = temp.resolve("saved-substitute-en.md");
    byte[] substituteBytes = "substituted content must not enter the job\n".getBytes();
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });
    PrepareWorkflow.ExistingEnglishReadHook swapBack =
        new PrepareWorkflow.ExistingEnglishReadHook() {
          @Override
          public void beforeNoFollowOpen(Path path) throws IOException {
            Files.move(path, savedOriginal);
            Files.write(path, substituteBytes);
          }

          @Override
          public void afterNoFollowOpen(Path path) throws IOException {
            Files.move(path, savedSubstitute);
            Files.move(savedOriginal, path);
          }
        };
    PrepareWorkflow workflow = new PrepareWorkflow(
        runner,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new WorkflowStateService(),
        new JnaAtomicExchange(),
        swapBack,
        (temporary, target) -> {
          throw new AssertionError("recovery preservation must not run");
        });

    PrepareWorkflow.PrepareResult result = workflow.prepare(
        fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    assertArrayEquals(priorBytes, Files.readAllBytes(prior));
    assertArrayEquals(substituteBytes, Files.readAllBytes(savedSubstitute));
    assertFalse(Files.exists(fixture.jobs().resolve("blog/essay")));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains("changed before it could be read")));
  }

  @Test
  void failedRecoveryMoveRetainsConflictingEnglishTemporaryBytes() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    String original = Files.readString(prior);
    byte[] firstEdit = original.replace(
        "Prior English body.", "First concurrent English edit.")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] secondEdit = original.replace(
        "Prior English body.", "Second concurrent English edit.")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });
    AtomicInteger exchanges = new AtomicInteger();
    AtomicExchange platform = new JnaAtomicExchange();
    AtomicExchange editingExchange = (first, second) -> {
      int exchange = exchanges.incrementAndGet();
      if (exchange == 1) {
        Files.write(first, firstEdit);
      } else if (exchange == 2) {
        Files.write(first, secondEdit);
      }
      platform.exchange(first, second);
    };
    Path[] recoveryTemporary = new Path[1];
    PrepareWorkflow.RecoveryFilePreserver failingPreserver = (temporary, target) -> {
      recoveryTemporary[0] = temporary;
      assertArrayEquals(secondEdit, Files.readAllBytes(temporary));
      throw new IOException("simulated recovery move failure");
    };
    PrepareWorkflow workflow = new PrepareWorkflow(
        runner,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new WorkflowStateService(),
        editingExchange,
        path -> { },
        failingPreserver);

    PrepareWorkflow.PrepareResult result = workflow.prepare(
        fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("stale", result.status());
    assertEquals(2, exchanges.get());
    assertArrayEquals(firstEdit, Files.readAllBytes(prior));
    assertNotNull(recoveryTemporary[0]);
    assertTrue(Files.exists(recoveryTemporary[0]));
    assertArrayEquals(secondEdit, Files.readAllBytes(recoveryTemporary[0]));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains(recoveryTemporary[0].toString())));
  }

  @Test
  void displacedEnglishReadFailurePreservesPriorBytesAfterExchange() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] previous = Files.readAllBytes(prior);
    Path preserved = temp.resolve("preserved-prior-en.md");
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });
    PrepareWorkflow.RecoveryFilePreserver preserver = (temporary, target) -> {
      Files.move(temporary, preserved);
      return preserved;
    };
    PrepareWorkflow.IoHooks ioHooks = new PrepareWorkflow.IoHooks() {
      @Override
      public byte[] readDisplacedEnglish(Path path) throws IOException {
        throw new IOException("simulated displaced English read failure");
      }
    };
    PrepareWorkflow workflow = new PrepareWorkflow(
        runner,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new WorkflowStateService(),
        new JnaAtomicExchange(),
        path -> { },
        preserver,
        path -> { },
        (target, temporary) -> { },
        ioHooks);

    PrepareWorkflow.PrepareResult result = workflow.prepare(
        fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("stale", result.status());
    assertTrue(Files.readString(prior).contains("Fresh English body."));
    assertTrue(Files.exists(preserved));
    assertArrayEquals(previous, Files.readAllBytes(preserved));
    assertTrue(Files.readString(fixture.source()).contains(
        "publicTranslationStatus: \"generated\""));
    assertEquals("stale", journal(fixture, result).get("state"));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains("replacement may already be live")
            && item.message().contains(preserved.toString())));
  }

  @Test
  void firstDraftConflictRetainsBytesMutatedThroughLinkedDescriptor() throws Exception {
    Fixture fixture = fixture();
    byte[] concurrent = "Concurrent English bytes must remain recoverable.\n"
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });
    AtomicInteger hookCalls = new AtomicInteger();
    PrepareWorkflow.FirstDraftInstallHook mutateLinkedInode = (target, temporary) -> {
      hookCalls.incrementAndGet();
      assertTrue(Files.isSameFile(target, temporary));
      try (FileChannel descriptor = FileChannel.open(target, StandardOpenOption.WRITE)) {
        descriptor.truncate(0);
        ByteBuffer bytes = ByteBuffer.wrap(concurrent);
        while (bytes.hasRemaining()) {
          descriptor.write(bytes);
        }
        descriptor.force(true);
      }
    };
    PrepareWorkflow workflow = new PrepareWorkflow(
        runner,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new WorkflowStateService(),
        new JnaAtomicExchange(),
        path -> { },
        (temporary, target) -> {
          throw new AssertionError("recovery preservation must not run");
        },
        path -> { },
        mutateLinkedInode);

    PrepareWorkflow.PrepareResult result = workflow.prepare(
        fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    Path target = fixture.review().resolve("blog/essay/en.md");
    assertEquals("stale", result.status());
    assertEquals(1, hookCalls.get());
    assertTrue(Files.exists(target));
    assertArrayEquals(concurrent, Files.readAllBytes(target));
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

  @Test
  void lockAcquisitionRejectsNamedFileReplacementAfterDescriptorOpen() throws Exception {
    Fixture fixture = fixture();
    Path lockPath = fixture.jobs().resolve("blog/essay.lock");
    Path openedLock = fixture.jobs().resolve("opened-lock");
    Path replacement = fixture.jobs().resolve("replacement-lock");
    Files.createDirectories(lockPath.getParent());
    Files.writeString(lockPath, "opened lock\n");
    Files.writeString(replacement, "replacement lock\n");
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });
    PrepareWorkflow.LockAcquisitionHook replaceAfterOpen = path -> {
      Files.move(path, openedLock);
      Files.move(replacement, path);
    };
    PrepareWorkflow workflow = new PrepareWorkflow(
        runner,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new WorkflowStateService(),
        new JnaAtomicExchange(),
        path -> { },
        (temporary, target) -> {
          throw new AssertionError("recovery preservation must not run");
        },
        replaceAfterOpen);

    PrepareWorkflow.PrepareResult result = workflow.prepare(
        fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    assertEquals("opened lock\n", Files.readString(openedLock));
    assertEquals("replacement lock\n", Files.readString(lockPath));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.field().equals("lock")));
  }

  @Test
  void firstJobInputWriteFailurePersistsCreatedThenFailedJournal() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] previous = Files.readAllBytes(prior);
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });
    PrepareWorkflow.IoHooks ioHooks = new PrepareWorkflow.IoHooks() {
      @Override
      public void beforeJobInputWrite(Path path) throws IOException {
        if (path.getFileName().toString().equals("ru.md")) {
          throw new IOException("simulated first job input write failure");
        }
      }
    };

    PrepareWorkflow.PrepareResult result = workflow(runner, new JnaAtomicExchange(), ioHooks)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    assertArrayEquals(previous, Files.readAllBytes(prior));
    assertTrue(Files.readString(fixture.source()).contains(
        "publicWorkflowStatus: \"translation_failed\""));
    Map<String, Object> journal = journal(fixture, result);
    assertEquals("failed", journal.get("state"));
    assertEquals(
        List.of("created", "failed"),
        historyStates(journal));
  }

  @Test
  void englishReplacementFailureNeverRecordsSucceededAndPreservesPrior() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] previous = Files.readAllBytes(prior);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });
    AtomicExchange failingExchange = (left, right) -> {
      throw new IOException("simulated durable EN replacement failure");
    };

    PrepareWorkflow.PrepareResult result =
        workflow(runner, failingExchange, new PrepareWorkflow.IoHooks() { })
            .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertArrayEquals(previous, Files.readAllBytes(prior));
    assertTrue(Files.readString(fixture.source()).contains(
        "publicWorkflowStatus: \"translation_failed\""));
    Map<String, Object> journal = journal(fixture, result);
    assertEquals("failed", journal.get("state"));
    assertEquals(
        List.of("created", "running", "running", "failed"),
        historyStates(journal));
  }

  @Test
  void postCommitJournalFailureReturnsReadyWithPendingEvidence() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    byte[] previous = Files.readAllBytes(prior);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });
    PrepareWorkflow.IoHooks ioHooks = new PrepareWorkflow.IoHooks() {
      @Override
      public void writeJournal(Path path, Map<String, Object> payload) throws IOException {
        if ("succeeded".equals(payload.get("state"))) {
          throw new IOException("simulated post-commit journal failure");
        }
        PrepareWorkflow.IoHooks.super.writeJournal(path, payload);
      }
    };

    PrepareWorkflow.PrepareResult result = workflow(runner, new JnaAtomicExchange(), ioHooks)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
    byte[] committed = Files.readAllBytes(prior);
    assertFalse(java.util.Arrays.equals(previous, committed));
    assertTrue(Files.readString(fixture.source()).contains(
        "publicWorkflowStatus: \"ready_for_review\""));
    assertTrue(result.diagnostics().stream()
        .anyMatch(item -> item.message().contains("journal")));
    Map<String, Object> journal = journal(fixture, result);
    assertEquals("running", journal.get("state"));
    assertEquals("prepare.commit_pending", journal.get("diagnostic"));
    assertEquals(sha256(committed), journal.get("expectedEnSha256"));
    assertEquals(
        List.of("created", "running", "running"),
        historyStates(journal));
  }

  @Test
  void failedJournalPersistenceDoesNotCreatePhantomHistory() throws Exception {
    Fixture fixture = fixture();
    RecordingRunner runner = new RecordingRunner(job -> {
      throw new AssertionError("runner must not start");
    });
    AtomicBoolean failFirstRunning = new AtomicBoolean(true);
    PrepareWorkflow.IoHooks ioHooks = new PrepareWorkflow.IoHooks() {
      @Override
      public void writeJournal(Path path, Map<String, Object> payload) throws IOException {
        if ("running".equals(payload.get("state"))
            && failFirstRunning.compareAndSet(true, false)) {
          throw new IOException("simulated running journal failure");
        }
        PrepareWorkflow.IoHooks.super.writeJournal(path, payload);
      }
    };

    PrepareWorkflow.PrepareResult result = workflow(runner, new JnaAtomicExchange(), ioHooks)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("translation_failed", result.status());
    assertEquals(0, runner.calls.get());
    Map<String, Object> journal = journal(fixture, result);
    assertEquals("failed", journal.get("state"));
    assertEquals(List.of("created", "failed"), historyStates(journal));
  }

  @Test
  void postCommitEnglishTemporaryCleanupFailureDoesNotChangeSuccess() throws Exception {
    Fixture fixture = fixture();
    Path prior = priorEnglish(fixture);
    RecordingRunner runner = new RecordingRunner(job -> {
      writeCandidate(job, null);
      return new CodexRunner.Run(0, "", "", false);
    });
    PrepareWorkflow.IoHooks ioHooks = new PrepareWorkflow.IoHooks() {
      @Override
      public void deleteEnglishTemporary(Path path) throws IOException {
        throw new IOException("simulated post-commit temporary cleanup failure");
      }
    };

    PrepareWorkflow.PrepareResult result = workflow(runner, new JnaAtomicExchange(), ioHooks)
        .prepare(fixture.vault(), "blog/Essay.md", fixture.review(), fixture.jobs());

    assertEquals("ready_for_review", result.status());
    assertTrue(Files.readString(prior).contains("Fresh English body."));
    assertTrue(Files.readString(fixture.source()).contains(
        "publicWorkflowStatus: \"ready_for_review\""));
    assertEquals("succeeded", journal(fixture, result).get("state"));
  }

  private PrepareWorkflow workflow(RecordingRunner runner) {
    return new PrepareWorkflow(runner, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private PrepareWorkflow workflow(
      RecordingRunner runner,
      AtomicExchange atomicExchange,
      PrepareWorkflow.IoHooks ioHooks) {
    return new PrepareWorkflow(
        runner,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new WorkflowStateService(),
        atomicExchange,
        path -> { },
        (temporary, target) -> temporary,
        path -> { },
        (target, temporary) -> { },
        ioHooks);
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

  private Fixture conceptFixture() throws Exception {
    Path vault = temp.resolve("vault");
    Path source = vault.resolve("concepts/Organisation.md");
    Files.createDirectories(source.getParent());
    Files.writeString(source, """
        ---
        id: organisation
        title: Organisation
        publish: true
        publicId: organisation
        publicCollection: concepts
        publicContentType: concept
        description: Русское описание.
        ---
        ## Определение

        Русское определение.
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
    writeCandidate(job, sourceHash, "Fresh English body.\n");
  }

  private static void writeCandidate(
      Path job,
      String sourceHash,
      String body) throws Exception {
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
        %s
        """.formatted(hash, body.stripTrailing()));
  }

  private static Map<String, Object> journal(
      Fixture fixture,
      PrepareWorkflow.PrepareResult result) throws Exception {
    return JSON.readValue(
        Files.readString(
            fixture.jobs().resolve("blog/essay").resolve(result.jobId()).resolve("job.json")),
        new TypeReference<>() { });
  }

  @SuppressWarnings("unchecked")
  private static List<Object> historyStates(Map<String, Object> journal) {
    return ((List<Map<String, Object>>) journal.get("history")).stream()
        .map(event -> event.get("state"))
        .toList();
  }

  private static String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
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
