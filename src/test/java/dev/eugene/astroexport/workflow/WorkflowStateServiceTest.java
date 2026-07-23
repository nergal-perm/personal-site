package dev.eugene.astroexport.workflow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.fs.AtomicExchange;
import dev.eugene.astroexport.fs.JnaAtomicExchange;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkflowStateServiceTest {
  private static final Instant UPDATED_AT = Instant.parse("2026-07-18T08:34:56Z");

  @TempDir
  Path temp;

  @Test
  void replacesOnlyOwnedWorkflowScalars() throws Exception {
    Path source = write("essay.md", richSource());

    new WorkflowStateService().updateWorkflowState(
        source,
        new WorkflowStateService.WorkflowUpdate(
            "ready_for_review", "generated", "Check \"term\": line 2"),
        UPDATED_AT);

    assertEquals(
        richSource()
            .replace("publicWorkflowStatus: \"stale\"",
                "publicWorkflowStatus: \"ready_for_review\"")
            .replace("publicTranslationStatus: \"reviewed\"",
                "publicTranslationStatus: \"generated\"")
            .replace("publicWorkflowUpdated: \"2026-07-17T09:00:00Z\"",
                "publicWorkflowUpdated: \"2026-07-18T08:34:56Z\"")
            .replace("publicWorkflowDiagnostic: \"Old message\"",
                "publicWorkflowDiagnostic: \"Check \\\"term\\\": line 2\""),
        Files.readString(source));
  }

  @Test
  void appendsMissingFieldsAndClearsTranslationStatus() throws Exception {
    Path source = write("essay.md", "---\ntitle: Essay\n---\nBody.\n");

    new WorkflowStateService().updateWorkflowState(
        source,
        new WorkflowStateService.WorkflowUpdate(
            "metadata_blocked", null, "missing publicId"),
        UPDATED_AT);

    assertEquals("""
        ---
        title: Essay
        publicWorkflowStatus: "metadata_blocked"
        publicTranslationStatus: ""
        publicWorkflowUpdated: "2026-07-18T08:34:56Z"
        publicWorkflowDiagnostic: "missing publicId"
        ---
        Body.
        """, Files.readString(source));
  }

  @Test
  void rejectsDuplicateYamlKeysWithoutChangingSource() throws Exception {
    String original = "---\ntitle: First\ntitle : Second\n---\nBody.\n";
    Path source = write("essay.md", original);

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowStateService().updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("stale", null, ""),
            UPDATED_AT));

    assertTrue(error.getMessage().contains("duplicate"));
    assertEquals(original, Files.readString(source));
  }

  @Test
  void rejectsDuplicateOwnedWorkflowKeysWithoutChangingSource() throws Exception {
    String original = """
        ---
        title: Essay
        publicWorkflowStatus: "stale"
        "publicWorkflowStatus" : "translating"
        ---
        Body.
        """;
    Path source = write("duplicate-workflow.md", original);

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowStateService().updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate(
                "ready_for_review", "generated", ""),
            UPDATED_AT));

    assertTrue(error.getMessage().contains("duplicate"));
    assertEquals(original, Files.readString(source));
    assertNoTemporaryFiles();
  }

  @Test
  void rejectsMalformedYamlWithoutChangingSource() throws Exception {
    String original = "---\ntitle: [unterminated\n---\n[[Body]]\n";
    Path source = write("malformed.md", original);

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowStateService().updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate(
                "metadata_blocked", null, "invalid"),
            UPDATED_AT));

    assertTrue(error.getMessage().contains("invalid YAML frontmatter"));
    assertEquals(original, Files.readString(source));
    assertNoTemporaryFiles();
  }

  @Test
  void temporaryWriteFailureLeavesOriginalSourceAndCleansStaging() throws Exception {
    Path source = write("temp-write.md", richSource());
    byte[] original = Files.readAllBytes(source);
    WorkflowStateService.IoHooks failingWrite = new WorkflowStateService.IoHooks() {
      @Override
      public void beforeTemporaryWrite(Path temporary) throws IOException {
        throw new IOException("simulated temp write failure");
      }
    };

    IOException error = assertThrows(
        IOException.class,
        () -> new WorkflowStateService(new JnaAtomicExchange(), failingWrite)
            .updateWorkflowState(
                source,
                new WorkflowStateService.WorkflowUpdate("translating", null, ""),
                UPDATED_AT));

    assertTrue(error.getMessage().contains("simulated temp write failure"));
    assertArrayEquals(original, Files.readAllBytes(source));
    assertNoTemporaryFiles();
  }

  @Test
  void rejectsAliasBasedWorkflowKeyWithoutChangingSource() throws Exception {
    String original = """
        ---
        keyName: &workflowKey publicWorkflowStatus
        *workflowKey: "stale"
        title: Keep
        ---
        Body.
        """;
    Path source = write("essay.md", original);

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowStateService().updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("stale", null, ""),
            UPDATED_AT));

    assertTrue(error.getMessage().contains("alias-based workflow key"));
    assertEquals(original, Files.readString(source));
    assertNoTemporaryFiles();
  }

  @Test
  void rejectsNonScalarWorkflowField() throws Exception {
    String original = """
        ---
        title: Essay
        publicWorkflowDiagnostic:
          nested: value
        ---
        Body.
        """;
    Path source = write("essay.md", original);

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowStateService().updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("stale", null, ""),
            UPDATED_AT));

    assertTrue(error.getMessage().contains("must be a scalar"));
    assertEquals(original, Files.readString(source));
  }

  @Test
  void replacesAlternateYamlSpellingsAtOwnedFieldPosition() throws Exception {
    for (String statusLine : new String[] {
        "publicWorkflowStatus : \"stale\"",
        "\"publicWorkflowStatus\": \"stale\""
    }) {
      String original = """
          ---
          title: Essay
          %s
          summary: Keep this position.
          publicTranslationStatus: "reviewed"
          publicWorkflowUpdated: "2026-07-17T09:00:00Z"
          publicWorkflowDiagnostic: "Old"
          ---
          Body.
          """.formatted(statusLine);
      Path source = write("alternate-" + Math.abs(statusLine.hashCode()) + ".md", original);

      new WorkflowStateService().updateWorkflowState(
          source,
          new WorkflowStateService.WorkflowUpdate("ready_for_review", "generated", ""),
          UPDATED_AT);

      assertEquals(
          original
              .replace(statusLine, "publicWorkflowStatus: \"ready_for_review\"")
              .replace(
                  "publicTranslationStatus: \"reviewed\"",
                  "publicTranslationStatus: \"generated\"")
              .replace(
                  "publicWorkflowUpdated: \"2026-07-17T09:00:00Z\"",
                  "publicWorkflowUpdated: \"2026-07-18T08:34:56Z\"")
              .replace(
                  "publicWorkflowDiagnostic: \"Old\"",
                  "publicWorkflowDiagnostic: \"\""),
          Files.readString(source));
    }
  }

  @Test
  void rejectsMissingFrontmatterWithoutChangingSource() throws Exception {
    String original = "# No frontmatter\n\n[[Body]]\n";
    Path source = write("missing-frontmatter.md", original);

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> new WorkflowStateService().updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("stale", null, ""),
            UPDATED_AT));

    assertTrue(error.getMessage().contains("YAML frontmatter"));
    assertEquals(original, Files.readString(source));
  }

  @Test
  void rejectsInvalidUpdatesWithoutChangingSource() throws Exception {
    Path source = write("invalid-update.md", richSource());
    byte[] original = Files.readAllBytes(source);
    WorkflowStateService service = new WorkflowStateService();

    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("unsupported", null, ""),
            UPDATED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("stale", "source", ""),
            UPDATED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("stale", null, null),
            UPDATED_AT));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("stale", null, ""),
            null));

    assertArrayEquals(original, Files.readAllBytes(source));
    assertNoTemporaryFiles();
  }

  @Test
  void rejectsChangedSourceSnapshotWithoutOverwritingIt() throws Exception {
    Path source = write("essay.md", richSource());
    byte[] expected = Files.readAllBytes(source);
    byte[] concurrent = richSource().replace("# Heading", "# Concurrent").getBytes(StandardCharsets.UTF_8);
    Files.write(source, concurrent);

    assertThrows(
        WorkflowStateService.ConcurrentFileUpdateException.class,
        () -> new WorkflowStateService().updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("ready_for_review", "reviewed", ""),
            UPDATED_AT,
            new WorkflowStateService.SnapshotGuard(source, expected)));

    assertArrayEquals(concurrent, Files.readAllBytes(source));
    assertNoTemporaryFiles();
  }

  @Test
  void rejectsChangedCompanionBeforeReplacingSource() throws Exception {
    Path source = write("essay.md", richSource());
    byte[] sourceBefore = Files.readAllBytes(source);
    Path companion = write("en.md", "reviewed English\n");
    byte[] expectedCompanion = Files.readAllBytes(companion);
    Files.writeString(companion, "concurrently edited English\n");

    assertThrows(
        WorkflowStateService.ConcurrentFileUpdateException.class,
        () -> new WorkflowStateService().updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("ready_for_review", "reviewed", ""),
            UPDATED_AT,
            new WorkflowStateService.SnapshotGuard(source, sourceBefore),
            new WorkflowStateService.SnapshotGuard(companion, expectedCompanion)));

    assertArrayEquals(sourceBefore, Files.readAllBytes(source));
    assertEquals("concurrently edited English\n", Files.readString(companion));
  }

  @Test
  void rollsBackEditInjectedAtAtomicCommitBoundary() throws Exception {
    Path source = write("essay.md", richSource());
    byte[] expected = Files.readAllBytes(source);
    byte[] concurrent = richSource().replace("# Heading", "# Concurrent").getBytes(StandardCharsets.UTF_8);
    AtomicInteger exchanges = new AtomicInteger();
    AtomicExchange platform = new JnaAtomicExchange();
    AtomicExchange injecting = (first, second) -> {
      if (exchanges.incrementAndGet() == 1) {
        Files.write(first, concurrent);
      }
      platform.exchange(first, second);
    };

    WorkflowStateService.ConcurrentFileUpdateException error = assertThrows(
        WorkflowStateService.ConcurrentFileUpdateException.class,
        () -> new WorkflowStateService(injecting).updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("ready_for_review", "reviewed", ""),
            UPDATED_AT,
            new WorkflowStateService.SnapshotGuard(source, expected)));

    assertTrue(error.getMessage().contains("commit boundary"));
    assertEquals(2, exchanges.get());
    assertArrayEquals(concurrent, Files.readAllBytes(source));
    assertNoTemporaryFiles();
  }

  @Test
  void refusesUnsafeFallbackWhenAtomicExchangeIsUnavailable() throws Exception {
    Path source = write("essay.md", richSource());
    byte[] expected = Files.readAllBytes(source);
    AtomicExchange unavailable = (first, second) -> {
      throw new AtomicExchange.AtomicExchangeUnavailableException("unavailable");
    };

    AtomicExchange.AtomicExchangeUnavailableException error = assertThrows(
        AtomicExchange.AtomicExchangeUnavailableException.class,
        () -> new WorkflowStateService(unavailable).updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("ready_for_review", "reviewed", ""),
            UPDATED_AT,
            new WorkflowStateService.SnapshotGuard(source, expected)));

    assertEquals("unavailable", error.getMessage());
    assertArrayEquals(expected, Files.readAllBytes(source));
    assertNoTemporaryFiles();
  }

  @Test
  void preservesDisplacedBytesWhenTargetChangesAfterExchange() throws Exception {
    Path source = write("essay.md", richSource());
    byte[] expected = Files.readAllBytes(source);
    byte[] concurrent = "---\ntitle: Concurrent\n---\nExternal.\n".getBytes(StandardCharsets.UTF_8);
    AtomicInteger exchanges = new AtomicInteger();
    AtomicExchange platform = new JnaAtomicExchange();
    AtomicExchange injecting = (first, second) -> {
      platform.exchange(first, second);
      if (exchanges.incrementAndGet() == 1) {
        Files.write(first, concurrent);
      }
    };

    WorkflowStateService.ConcurrentFileUpdateException error = assertThrows(
        WorkflowStateService.ConcurrentFileUpdateException.class,
        () -> new WorkflowStateService(injecting).updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("ready_for_review", "reviewed", ""),
            UPDATED_AT,
            new WorkflowStateService.SnapshotGuard(source, expected)));

    assertArrayEquals(concurrent, Files.readAllBytes(source));
    assertNotNull(error.preservedPath());
    assertArrayEquals(expected, Files.readAllBytes(error.preservedPath()));
    assertTrue(error.preservedPath().getParent().getFileName().toString()
        .startsWith(".essay.md.astro-export-conflict-"));
    assertNoTemporaryFiles();
  }

  @Test
  void preservesSecondEditObservedDuringRollback() throws Exception {
    Path source = write("essay.md", richSource());
    byte[] expected = Files.readAllBytes(source);
    byte[] firstEdit = "---\ntitle: First concurrent edit\n---\nFirst.\n"
        .getBytes(StandardCharsets.UTF_8);
    byte[] secondEdit = "---\ntitle: Second concurrent edit\n---\nSecond.\n"
        .getBytes(StandardCharsets.UTF_8);
    AtomicInteger exchanges = new AtomicInteger();
    AtomicExchange platform = new JnaAtomicExchange();
    AtomicExchange injecting = (first, second) -> {
      int exchange = exchanges.incrementAndGet();
      if (exchange == 1) {
        Files.write(first, firstEdit);
      } else if (exchange == 2) {
        Files.write(first, secondEdit);
      }
      platform.exchange(first, second);
    };

    WorkflowStateService.ConcurrentFileUpdateException error = assertThrows(
        WorkflowStateService.ConcurrentFileUpdateException.class,
        () -> new WorkflowStateService(injecting).updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate("ready_for_review", "reviewed", ""),
            UPDATED_AT,
            new WorkflowStateService.SnapshotGuard(source, expected)));

    assertEquals(2, exchanges.get());
    assertArrayEquals(firstEdit, Files.readAllBytes(source));
    assertNotNull(error.preservedPath());
    assertArrayEquals(secondEdit, Files.readAllBytes(error.preservedPath()));
  }

  @Test
  void clearBlanksOwnedValues() throws Exception {
    Path source = write("essay.md", richSource());

    new WorkflowStateService().clearWorkflowState(source, UPDATED_AT);

    String rendered = Files.readString(source);
    assertTrue(rendered.contains("publicWorkflowStatus: \"\""));
    assertTrue(rendered.contains("publicTranslationStatus: \"\""));
    assertTrue(rendered.contains("publicWorkflowUpdated: \"2026-07-18T08:34:56Z\""));
    assertTrue(rendered.contains("publicWorkflowDiagnostic: \"\""));
  }

  @Test
  void preservesSourcePermissionsAcrossGuardedExchange() throws Exception {
    Path source = write("essay.md", richSource());
    Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-r-----"));

    new WorkflowStateService().updateWorkflowState(
        source,
        new WorkflowStateService.WorkflowUpdate("stale", null, ""),
        UPDATED_AT);

    assertEquals(
        PosixFilePermissions.fromString("rw-r-----"),
        Files.getPosixFilePermissions(source));
  }

  @Test
  void retainsTemporaryRecoveryFileWhenConflictDirectoryCreationFails() throws Exception {
    Path source = write("essay.md", richSource());
    byte[] expected = Files.readAllBytes(source);
    byte[] concurrent = "---\ntitle: Concurrent\n---\nExternal.\n".getBytes(StandardCharsets.UTF_8);
    AtomicExchange platform = new JnaAtomicExchange();
    AtomicExchange injecting = (first, second) -> {
      platform.exchange(first, second);
      Files.write(first, concurrent);
      Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("r-x------"));
    };

    WorkflowStateService.ConcurrentFileUpdateException error;
    try {
      error = assertThrows(
          WorkflowStateService.ConcurrentFileUpdateException.class,
          () -> new WorkflowStateService(injecting).updateWorkflowState(
              source,
              new WorkflowStateService.WorkflowUpdate("ready_for_review", "reviewed", ""),
              UPDATED_AT,
              new WorkflowStateService.SnapshotGuard(source, expected)));
    } finally {
      Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
    }

    assertNotNull(error.preservedPath());
    assertTrue(Files.exists(error.preservedPath()));
    assertArrayEquals(expected, Files.readAllBytes(error.preservedPath()));
  }

  @Test
  void retainsTemporaryRecoveryFileWhenPreservationMoveFails() throws Exception {
    Path source = write("preservation-move.md", richSource());
    byte[] expected = Files.readAllBytes(source);
    byte[] concurrent = "---\ntitle: Concurrent replacement\n---\nExternal edit.\n"
        .getBytes(StandardCharsets.UTF_8);
    AtomicExchange platform = new JnaAtomicExchange();
    AtomicExchange injecting = (first, second) -> {
      platform.exchange(first, second);
      Files.write(first, concurrent);
    };
    Path[] retained = new Path[1];
    WorkflowStateService.IoHooks failingMove = new WorkflowStateService.IoHooks() {
      @Override
      public Path preserve(Path temporary, Path target) throws IOException {
        retained[0] = temporary;
        assertArrayEquals(expected, Files.readAllBytes(temporary));
        throw new IOException("simulated preservation move failure");
      }
    };

    WorkflowStateService.ConcurrentFileUpdateException error = assertThrows(
        WorkflowStateService.ConcurrentFileUpdateException.class,
        () -> new WorkflowStateService(injecting, failingMove).updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate(
                "ready_for_review", "reviewed", ""),
            UPDATED_AT,
            new WorkflowStateService.SnapshotGuard(source, expected)));

    assertArrayEquals(concurrent, Files.readAllBytes(source));
    assertEquals(retained[0], error.preservedPath());
    assertNotNull(error.preservedPath());
    assertTrue(Files.exists(error.preservedPath()));
    assertArrayEquals(expected, Files.readAllBytes(error.preservedPath()));
  }

  @Test
  void exchangeFailureLeavesOriginalSourceAndCleansStaging() throws Exception {
    Path source = write("exchange-failure.md", richSource());
    byte[] expected = Files.readAllBytes(source);
    AtomicExchange failing = (first, second) -> {
      throw new IOException("simulated exchange failure");
    };

    IOException error = assertThrows(
        IOException.class,
        () -> new WorkflowStateService(failing).updateWorkflowState(
            source,
            new WorkflowStateService.WorkflowUpdate(
                "translation_failed", null, "failed"),
            UPDATED_AT,
            new WorkflowStateService.SnapshotGuard(source, expected)));

    assertTrue(error.getMessage().contains("simulated exchange failure"));
    assertArrayEquals(expected, Files.readAllBytes(source));
    assertNoTemporaryFiles();
  }

  @Test
  void detectsOpenDescriptorMutationBeforeFinalVerification() throws Exception {
    Path source = write("essay.md", richSource());
    byte[] expected = Files.readAllBytes(source);
    byte[] concurrent = richSource().replace("# Heading", "# Open descriptor edit")
        .getBytes(StandardCharsets.UTF_8);
    AtomicInteger exchanges = new AtomicInteger();
    AtomicExchange platform = new JnaAtomicExchange();

    try (FileChannel descriptor = FileChannel.open(source, StandardOpenOption.WRITE)) {
      AtomicExchange injecting = (first, second) -> {
        platform.exchange(first, second);
        if (exchanges.incrementAndGet() == 1) {
          descriptor.truncate(0);
          descriptor.position(0);
          descriptor.write(ByteBuffer.wrap(concurrent));
          descriptor.force(true);
        }
      };

      WorkflowStateService.ConcurrentFileUpdateException error = assertThrows(
          WorkflowStateService.ConcurrentFileUpdateException.class,
          () -> new WorkflowStateService(injecting).updateWorkflowState(
              source,
              new WorkflowStateService.WorkflowUpdate("ready_for_review", "reviewed", ""),
              UPDATED_AT,
              new WorkflowStateService.SnapshotGuard(source, expected)));

      assertTrue(error.getMessage().contains("commit boundary"));
    }

    assertEquals(2, exchanges.get());
    assertArrayEquals(concurrent, Files.readAllBytes(source));
    assertNoTemporaryFiles();
  }

  private Path write(String name, String content) throws IOException {
    Path path = temp.resolve(name);
    Files.writeString(path, content);
    return path;
  }

  private void assertNoTemporaryFiles() throws IOException {
    try (var paths = Files.list(temp)) {
      assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }
  }

  private static String richSource() {
    return """
        ---
        id: "essay-one"
        title: 'Keep quotes: literally'
        aliases:
          - First alias
          - "Second: alias"
        publicWorkflowStatus: "stale"
        summary: >-
          First line
          and second line.
        publicTranslationStatus: "reviewed"
        publicWorkflowUpdated: "2026-07-17T09:00:00Z"
        description: |-
          Line one.
          Line two.
        publicWorkflowDiagnostic: "Old message"
        publish: true
        ---
        # Heading

        Body with [[Wiki link|label]] and `code: intact`.
        """;
  }
}
