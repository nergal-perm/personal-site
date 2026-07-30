package dev.eugene.astroexport.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.fs.JnaAtomicExchange;
import dev.eugene.astroexport.workflow.WorkflowStateService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublishedSnapshotStoreTest {
  @TempDir
  Path temp;

  @Test
  void commitsFirstTripleAndReplacesAllFilesTogether() throws Exception {
    Path page = temp.resolve("review/blog/essay");
    Files.createDirectories(page);
    PublishedSnapshotStore store = new PublishedSnapshotStore();

    try (PublishedSnapshotStore.PendingSnapshot first =
        store.stageSemantic(page, bytes("ru-v1\n"), bytes("en-v1\n"), bytes(mapV1()))) {
      assertTrue(first.commit(List.of()).recoveryPaths().isEmpty());
    }
    assertTriple(page, "ru-v1\n", "en-v1\n", mapV1());

    try (PublishedSnapshotStore.PendingSnapshot second =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()))) {
      assertTrue(second.commit(List.of()).recoveryPaths().isEmpty());
    }
    assertTriple(page, "ru-v2\n", "en-v2\n", mapV2());
  }

  @Test
  void guardConflictAfterVisibleSwapRollsBackTheWholePair() throws Exception {
    Path page = existingTriple("ru-v1\n", "en-v1\n", mapV1());
    Path source = temp.resolve("source.md");
    Files.writeString(source, "expected\n");
    byte[] expected = Files.readAllBytes(source);
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void afterVisibleCommit(Path published) throws IOException {
            Files.writeString(source, "changed\n");
          }
        });

    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()))) {
      assertThrows(
          PublishedSnapshotStore.ConcurrentPublishedSnapshotException.class,
          () -> pending.commit(List.of(
              new WorkflowStateService.SnapshotGuard(source, expected))));
    }

    assertTriple(page, "ru-v1\n", "en-v1\n", mapV1());
  }

  @Test
  void commitsAndRollsBackRuEnAndReferenceMapTogether() throws Exception {
    Path page = existingTriple("ru-v1", "en-v1", mapV1());
    Path source = temp.resolve("source.md");
    Files.writeString(source, "expected\n");
    WorkflowStateService.SnapshotGuard guard =
        new WorkflowStateService.SnapshotGuard(source, Files.readAllBytes(source));
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void afterVisibleCommit(Path published) throws IOException {
            Files.writeString(source, "changed\n");
          }
        });

    try (PublishedSnapshotStore.PendingSnapshot pending = store.stageSemantic(
        page, bytes("ru-v2"), bytes("en-v2"), bytes(mapV2()))) {
      assertThrows(PublishedSnapshotStore.ConcurrentPublishedSnapshotException.class,
          () -> pending.commit(List.of(guard)));
    }

    assertTriple(page, "ru-v1", "en-v1", mapV1());
  }

  @Test
  void rejectsLegacyPartialOrExtraPublishedLayoutsInSemanticMode() throws Exception {
    Path page = existingPair("ru", "en");
    PublishedSnapshotStore store = new PublishedSnapshotStore();

    assertThrows(IllegalArgumentException.class,
        () -> store.stageSemantic(
            page, bytes("new-ru"), bytes("new-en"), bytes(mapV1())));
  }

  @Test
  void exchangeFailurePreservesThePreviousPair() throws Exception {
    Path page = existingTriple("ru-v1\n", "en-v1\n", mapV1());
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        (first, second) -> { throw new IOException("exchange failed"); },
        new PublishedSnapshotStore.IoHooks() { });

    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()))) {
      IllegalStateException error =
          assertThrows(IllegalStateException.class, () -> pending.commit(List.of()));
      assertTrue(error.getMessage().contains("exchange failed"));
    }

    assertTriple(page, "ru-v1\n", "en-v1\n", mapV1());
  }

  @Test
  void cleanupFailureReportsTheDisplacedPairAfterCommit() throws Exception {
    Path page = existingTriple("ru-v1\n", "en-v1\n", mapV1());
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void deleteTree(Path root) throws IOException {
            throw new IOException("cleanup failed");
          }
        });

    PublishedSnapshotStore.CommitResult result;
    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()))) {
      result = pending.commit(List.of());
    }

    assertTriple(page, "ru-v2\n", "en-v2\n", mapV2());
    assertEquals(1, result.recoveryPaths().size());
    assertTrue(Files.isDirectory(result.recoveryPaths().getFirst()));
  }

  @Test
  void rollbackFailureReportsCandidateOwnershipAndDisplacedRecoveryPath()
      throws Exception {
    Path page = existingTriple("ru-v1\n", "en-v1\n", mapV1());
    Path source = temp.resolve("source.md");
    Files.writeString(source, "expected\n");
    byte[] expected = Files.readAllBytes(source);
    JnaAtomicExchange realExchange = new JnaAtomicExchange();
    AtomicInteger exchangeCalls = new AtomicInteger();
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        (first, second) -> {
          if (exchangeCalls.incrementAndGet() == 1) {
            realExchange.exchange(first, second);
            return;
          }
          throw new IOException("rollback failed");
        },
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void afterVisibleCommit(Path published) throws IOException {
            Files.writeString(source, "changed\n");
          }
        });

    PublishedSnapshotStore.PublishedSnapshotRecoveryException error;
    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()))) {
      error = assertThrows(
          PublishedSnapshotStore.PublishedSnapshotRecoveryException.class,
          () -> pending.commit(List.of(
              new WorkflowStateService.SnapshotGuard(source, expected))));
    }

    assertTriple(page, "ru-v2\n", "en-v2\n", mapV2());
    assertEquals(
        PublishedSnapshotStore.RecoveryDisposition.CANDIDATE_VISIBLE,
        error.disposition());
    assertEquals(page.resolve("published").toAbsolutePath().normalize(),
        error.publishedPath());
    assertEquals(1, error.recoveryPaths().size());
    assertDirectoryTriple(error.recoveryPaths().getFirst(), "ru-v1\n", "en-v1\n", mapV1());
  }

  @Test
  void stagedCleanupFailureReportsUncommittedCandidateRecoveryPath()
      throws Exception {
    Path page = existingTriple("ru-v1\n", "en-v1\n", mapV1());
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void deleteTree(Path root) throws IOException {
            throw new IOException("cleanup failed");
          }
        });
    PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()));

    PublishedSnapshotStore.PublishedSnapshotRecoveryException error =
        assertThrows(
            PublishedSnapshotStore.PublishedSnapshotRecoveryException.class,
            pending::close);

    assertTriple(page, "ru-v1\n", "en-v1\n", mapV1());
    assertEquals(
        PublishedSnapshotStore.RecoveryDisposition.STAGED_CANDIDATE,
        error.disposition());
    assertEquals(page.resolve("published").toAbsolutePath().normalize(),
        error.publishedPath());
    assertEquals(1, error.recoveryPaths().size());
    assertDirectoryTriple(error.recoveryPaths().getFirst(), "ru-v2\n", "en-v2\n", mapV2());
  }

  @Test
  void uncheckedPostVisibleFailureRollsBackBeforeClose() throws Exception {
    Path page = existingTriple("ru-v1\n", "en-v1\n", mapV1());
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void afterVisibleCommit(Path published) {
            throw new IllegalStateException("hook failed");
          }
        });

    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()))) {
      IllegalStateException error =
          assertThrows(IllegalStateException.class, () -> pending.commit(List.of()));
      assertEquals("hook failed", error.getMessage());
    }

    assertTriple(page, "ru-v1\n", "en-v1\n", mapV1());
  }

  @Test
  void forcesStagingPairBeforeStageReturnsAndPageBeforeFirstCommitReturns()
      throws Exception {
    Path page = temp.resolve("review/blog/essay").toAbsolutePath().normalize();
    Files.createDirectories(page);
    List<String> events = new ArrayList<>();
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void forceDirectory(Path directory) throws IOException {
            if (directory.equals(page)) {
              assertTriple(page, "ru-v1\n", "en-v1\n", mapV1());
              events.add("page");
              return;
            }
            assertTrue(directory.getFileName().toString().startsWith(
                ".published-stage-"));
            assertDirectoryTriple(directory, "ru-v1\n", "en-v1\n", mapV1());
            events.add("staging");
          }
        });

    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v1\n"), bytes("en-v1\n"), bytes(mapV1()))) {
      assertEquals(List.of("staging"), events);
      pending.commit(List.of());
      assertEquals(List.of("staging", "page"), events);
    }
  }

  @Test
  void replacementForcesPageBeforeDeletingDisplacedPair() throws Exception {
    Path page = existingTriple("ru-v1\n", "en-v1\n", mapV1())
        .toAbsolutePath()
        .normalize();
    List<String> events = new ArrayList<>();
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void forceDirectory(Path directory) throws IOException {
            if (directory.equals(page)) {
              assertTriple(page, "ru-v2\n", "en-v2\n", mapV2());
              events.add("page");
              return;
            }
            assertDirectoryTriple(directory, "ru-v2\n", "en-v2\n", mapV2());
            events.add("staging");
          }

          @Override
          public void deleteTree(Path root) throws IOException {
            events.add("cleanup");
            PublishedSnapshotStore.IoHooks.super.deleteTree(root);
          }
        });

    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()))) {
      assertEquals(List.of("staging"), events);
      pending.commit(List.of());
    }

    assertEquals(List.of("staging", "page", "cleanup"), events);
  }

  @Test
  void firstPublicationPageForceFailureRollsBackAndForcesRollbackMetadata()
      throws Exception {
    Path page = temp.resolve("review/blog/essay").toAbsolutePath().normalize();
    Files.createDirectories(page);
    AtomicInteger pageForces = new AtomicInteger();
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void forceDirectory(Path directory) throws IOException {
            if (directory.equals(page)
                && pageForces.incrementAndGet() == 1) {
              throw new IOException("page force failed");
            }
          }
        });

    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v1\n"), bytes("en-v1\n"), bytes(mapV1()))) {
      IllegalStateException error =
          assertThrows(IllegalStateException.class, () -> pending.commit(List.of()));
      assertTrue(error.getMessage().contains("page force failed"));
    }

    assertEquals(2, pageForces.get());
    assertEquals(false, Files.exists(page.resolve("published")));
  }

  @Test
  void replacementRollbackForceFailureReportsPreservedCandidateRecovery()
      throws Exception {
    Path page = existingTriple("ru-v1\n", "en-v1\n", mapV1())
        .toAbsolutePath()
        .normalize();
    AtomicInteger pageForces = new AtomicInteger();
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void forceDirectory(Path directory) throws IOException {
            if (directory.equals(page)) {
              pageForces.incrementAndGet();
              throw new IOException("page force failed");
            }
          }
        });

    PublishedSnapshotStore.PublishedSnapshotRecoveryException error;
    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()))) {
      error = assertThrows(
          PublishedSnapshotStore.PublishedSnapshotRecoveryException.class,
          () -> pending.commit(List.of()));
    }

    assertEquals(2, pageForces.get());
    assertTriple(page, "ru-v1\n", "en-v1\n", mapV1());
    assertEquals(
        PublishedSnapshotStore.RecoveryDisposition.CANDIDATE_VISIBLE,
        error.disposition());
    assertEquals(page.resolve("published"), error.publishedPath());
    assertEquals(1, error.recoveryPaths().size());
    assertDirectoryTriple(error.recoveryPaths().getFirst(), "ru-v2\n", "en-v2\n", mapV2());
  }

  @Test
  void stagingDurabilityAndCleanupFailureReportsExactCandidatePath()
      throws Exception {
    Path page = temp.resolve("review/blog/essay").toAbsolutePath().normalize();
    Files.createDirectories(page);
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void forceDirectory(Path directory) throws IOException {
            if (directory.getFileName().toString().startsWith(
                ".published-stage-")) {
              throw new IOException("stage write force failed");
            }
          }

          @Override
          public void deleteTree(Path root) throws IOException {
            throw new IOException("stage cleanup failed");
          }
        });

    PublishedSnapshotStore.PublishedSnapshotRecoveryException error =
        assertThrows(
            PublishedSnapshotStore.PublishedSnapshotRecoveryException.class,
            () -> store.stageSemantic(page, bytes("ru-v1\n"), bytes("en-v1\n"), bytes(mapV1())));

    assertEquals(
        PublishedSnapshotStore.RecoveryDisposition.STAGED_CANDIDATE,
        error.disposition());
    assertEquals(page.resolve("published"), error.publishedPath());
    assertEquals(1, error.recoveryPaths().size());
    Path recovery = error.recoveryPaths().getFirst();
    assertEquals(page, recovery.getParent());
    assertTrue(recovery.getFileName().toString().startsWith(
        ".published-stage-"));
    assertDirectoryTriple(recovery, "ru-v1\n", "en-v1\n", mapV1());
    assertEquals("stage write force failed", error.getCause().getMessage());
    assertEquals(1, error.getCause().getSuppressed().length);
    assertEquals(
        "stage cleanup failed",
        error.getCause().getSuppressed()[0].getMessage());
  }

  @Test
  void uncheckedStagingForceCleansCandidateAndRethrowsOriginal()
      throws Exception {
    Path page = temp.resolve("review/blog/essay").toAbsolutePath().normalize();
    Files.createDirectories(page);
    IllegalStateException forceFailure =
        new IllegalStateException("unchecked stage force failed");
    AtomicInteger cleanups = new AtomicInteger();
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void forceDirectory(Path directory) {
            throw forceFailure;
          }

          @Override
          public void deleteTree(Path root) throws IOException {
            cleanups.incrementAndGet();
            PublishedSnapshotStore.IoHooks.super.deleteTree(root);
          }
        });

    IllegalStateException error = assertThrows(
        IllegalStateException.class,
        () -> store.stageSemantic(page, bytes("ru-v1\n"), bytes("en-v1\n"), bytes(mapV1())));

    assertSame(forceFailure, error);
    assertEquals(1, cleanups.get());
    assertTrue(stagingDirectories(page).isEmpty());
  }

  @Test
  void uncheckedStagingForceAndCleanupFailureReportRecoveryEvidence()
      throws Exception {
    Path page = temp.resolve("review/blog/essay").toAbsolutePath().normalize();
    Files.createDirectories(page);
    IllegalStateException forceFailure =
        new IllegalStateException("unchecked stage force failed");
    IllegalStateException cleanupFailure =
        new IllegalStateException("unchecked stage cleanup failed");
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void forceDirectory(Path directory) {
            throw forceFailure;
          }

          @Override
          public void deleteTree(Path root) {
            throw cleanupFailure;
          }
        });

    PublishedSnapshotStore.PublishedSnapshotRecoveryException error =
        assertThrows(
            PublishedSnapshotStore.PublishedSnapshotRecoveryException.class,
            () -> store.stageSemantic(page, bytes("ru-v1\n"), bytes("en-v1\n"), bytes(mapV1())));

    assertEquals(
        PublishedSnapshotStore.RecoveryDisposition.STAGED_CANDIDATE,
        error.disposition());
    assertEquals(page.resolve("published"), error.publishedPath());
    assertSame(forceFailure, error.getCause());
    assertEquals(1, forceFailure.getSuppressed().length);
    assertSame(cleanupFailure, forceFailure.getSuppressed()[0]);
    assertEquals(1, error.recoveryPaths().size());
    assertDirectoryTriple(error.recoveryPaths().getFirst(), "ru-v1\n", "en-v1\n", mapV1());
  }

  @Test
  void checkedStagingForceAndUncheckedCleanupFailureReportRecoveryEvidence()
      throws Exception {
    Path page = temp.resolve("review/blog/essay").toAbsolutePath().normalize();
    Files.createDirectories(page);
    IOException forceFailure = new IOException("checked stage force failed");
    IllegalStateException cleanupFailure =
        new IllegalStateException("unchecked stage cleanup failed");
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void forceDirectory(Path directory) throws IOException {
            throw forceFailure;
          }

          @Override
          public void deleteTree(Path root) {
            throw cleanupFailure;
          }
        });

    PublishedSnapshotStore.PublishedSnapshotRecoveryException error =
        assertThrows(
            PublishedSnapshotStore.PublishedSnapshotRecoveryException.class,
            () -> store.stageSemantic(page, bytes("ru-v1\n"), bytes("en-v1\n"), bytes(mapV1())));

    assertSame(forceFailure, error.getCause());
    assertEquals(1, forceFailure.getSuppressed().length);
    assertSame(cleanupFailure, forceFailure.getSuppressed()[0]);
    assertEquals(
        PublishedSnapshotStore.RecoveryDisposition.STAGED_CANDIDATE,
        error.disposition());
    assertDirectoryTriple(error.recoveryPaths().getFirst(), "ru-v1\n", "en-v1\n", mapV1());
  }

  @Test
  void uncheckedPageForceRollsBackAndRethrowsOriginal() throws Exception {
    Path page = existingTriple("ru-v1\n", "en-v1\n", mapV1())
        .toAbsolutePath()
        .normalize();
    IllegalStateException forceFailure =
        new IllegalStateException("unchecked page force failed");
    AtomicInteger pageForces = new AtomicInteger();
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void forceDirectory(Path directory) {
            if (directory.equals(page)
                && pageForces.incrementAndGet() == 1) {
              throw forceFailure;
            }
          }
        });

    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()))) {
      IllegalStateException error =
          assertThrows(IllegalStateException.class, () -> pending.commit(List.of()));
      assertSame(forceFailure, error);
    }

    assertEquals(2, pageForces.get());
    assertTriple(page, "ru-v1\n", "en-v1\n", mapV1());
    assertTrue(stagingDirectories(page).isEmpty());
  }

  @Test
  void uncheckedRollbackForceReportsCandidateVisibleRecoveryEvidence()
      throws Exception {
    Path page = existingTriple("ru-v1\n", "en-v1\n", mapV1())
        .toAbsolutePath()
        .normalize();
    IllegalStateException visibleForceFailure =
        new IllegalStateException("unchecked page force failed");
    IllegalStateException rollbackForceFailure =
        new IllegalStateException("unchecked rollback force failed");
    AtomicInteger pageForces = new AtomicInteger();
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void forceDirectory(Path directory) {
            if (!directory.equals(page)) {
              return;
            }
            if (pageForces.incrementAndGet() == 1) {
              throw visibleForceFailure;
            }
            throw rollbackForceFailure;
          }
        });

    PublishedSnapshotStore.PublishedSnapshotRecoveryException error;
    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()))) {
      error = assertThrows(
          PublishedSnapshotStore.PublishedSnapshotRecoveryException.class,
          () -> pending.commit(List.of()));
    }

    assertEquals(2, pageForces.get());
    assertTriple(page, "ru-v1\n", "en-v1\n", mapV1());
    assertEquals(
        PublishedSnapshotStore.RecoveryDisposition.CANDIDATE_VISIBLE,
        error.disposition());
    assertEquals(page.resolve("published"), error.publishedPath());
    assertSame(rollbackForceFailure, error.getCause());
    assertEquals(1, rollbackForceFailure.getSuppressed().length);
    assertSame(visibleForceFailure, rollbackForceFailure.getSuppressed()[0]);
    assertEquals(1, error.recoveryPaths().size());
    assertDirectoryTriple(error.recoveryPaths().getFirst(), "ru-v2\n", "en-v2\n", mapV2());
  }

  @Test
  void uncheckedCommittedCleanupReturnsNonBlockingRecoveryPath()
      throws Exception {
    Path page = existingTriple("ru-v1\n", "en-v1\n", mapV1())
        .toAbsolutePath()
        .normalize();
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void deleteTree(Path root) {
            throw new IllegalStateException("unchecked committed cleanup failed");
          }
        });

    PublishedSnapshotStore.CommitResult result;
    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()))) {
      result = pending.commit(List.of());
    }

    assertTriple(page, "ru-v2\n", "en-v2\n", mapV2());
    assertEquals(1, result.recoveryPaths().size());
    assertDirectoryTriple(result.recoveryPaths().getFirst(), "ru-v1\n", "en-v1\n", mapV1());
  }

  @Test
  void uncheckedCloseCleanupReportsStagedCandidateRecovery() throws Exception {
    Path page = existingTriple("ru-v1\n", "en-v1\n", mapV1())
        .toAbsolutePath()
        .normalize();
    IllegalStateException cleanupFailure =
        new IllegalStateException("unchecked close cleanup failed");
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void deleteTree(Path root) {
            throw cleanupFailure;
          }
        });
    PublishedSnapshotStore.PendingSnapshot pending =
        store.stageSemantic(page, bytes("ru-v2\n"), bytes("en-v2\n"), bytes(mapV2()));

    PublishedSnapshotStore.PublishedSnapshotRecoveryException error =
        assertThrows(
            PublishedSnapshotStore.PublishedSnapshotRecoveryException.class,
            pending::close);

    assertEquals(
        PublishedSnapshotStore.RecoveryDisposition.STAGED_CANDIDATE,
        error.disposition());
    assertEquals(page.resolve("published"), error.publishedPath());
    assertSame(cleanupFailure, error.getCause());
    assertEquals(1, error.recoveryPaths().size());
    assertDirectoryTriple(error.recoveryPaths().getFirst(), "ru-v2\n", "en-v2\n", mapV2());
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private Path existingPair(String russian, String english) throws IOException {
    Path page = temp.resolve("review/blog/essay");
    Files.createDirectories(page.resolve("published"));
    Files.writeString(page.resolve("published/ru.md"), russian);
    Files.writeString(page.resolve("published/en.md"), english);
    return page;
  }

  private Path existingTriple(String russian, String english, String references)
      throws IOException {
    Path page = existingPair(russian, english);
    Files.writeString(page.resolve("published/references.json"), references);
    return page;
  }

  private static void assertPair(Path page, String russian, String english)
      throws IOException {
    assertEquals(russian, Files.readString(page.resolve("published/ru.md")));
    assertEquals(english, Files.readString(page.resolve("published/en.md")));
  }

  private static void assertTriple(
      Path page,
      String russian,
      String english,
      String references) throws IOException {
    assertPair(page, russian, english);
    assertEquals(references, Files.readString(page.resolve("published/references.json")));
  }

  private static void assertDirectoryPair(
      Path directory,
      String russian,
      String english) throws IOException {
    assertEquals(russian, Files.readString(directory.resolve("ru.md")));
    assertEquals(english, Files.readString(directory.resolve("en.md")));
  }

  private static void assertDirectoryTriple(
      Path directory,
      String russian,
      String english,
      String references) throws IOException {
    assertDirectoryPair(directory, russian, english);
    assertEquals(references, Files.readString(directory.resolve("references.json")));
  }

  private static String mapV1() {
    return "{\"schemaVersion\":1,\"ruSha256\":\"ru-v1\",\"enSha256\":\"en-v1\",\"order\":[],\"references\":{}}\n";
  }

  private static String mapV2() {
    return "{\"schemaVersion\":1,\"ruSha256\":\"ru-v2\",\"enSha256\":\"en-v2\",\"order\":[],\"references\":{}}\n";
  }

  private static List<Path> stagingDirectories(Path page) throws IOException {
    try (var paths = Files.list(page)) {
      return paths
          .filter(path -> path.getFileName().toString().startsWith(
              ".published-stage-"))
          .toList();
    }
  }
}
