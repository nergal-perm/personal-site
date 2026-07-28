package dev.eugene.astroexport.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.fs.JnaAtomicExchange;
import dev.eugene.astroexport.workflow.WorkflowStateService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublishedSnapshotStoreTest {
  @TempDir
  Path temp;

  @Test
  void commitsFirstPairAndReplacesBothFilesTogether() throws Exception {
    Path page = temp.resolve("review/blog/essay");
    Files.createDirectories(page);
    PublishedSnapshotStore store = new PublishedSnapshotStore();

    try (PublishedSnapshotStore.PendingSnapshot first =
        store.stage(page, bytes("ru-v1\n"), bytes("en-v1\n"))) {
      assertTrue(first.commit(List.of()).recoveryPaths().isEmpty());
    }
    assertPair(page, "ru-v1\n", "en-v1\n");

    try (PublishedSnapshotStore.PendingSnapshot second =
        store.stage(page, bytes("ru-v2\n"), bytes("en-v2\n"))) {
      assertTrue(second.commit(List.of()).recoveryPaths().isEmpty());
    }
    assertPair(page, "ru-v2\n", "en-v2\n");
  }

  @Test
  void guardConflictAfterVisibleSwapRollsBackTheWholePair() throws Exception {
    Path page = existingPair("ru-v1\n", "en-v1\n");
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
        store.stage(page, bytes("ru-v2\n"), bytes("en-v2\n"))) {
      assertThrows(
          PublishedSnapshotStore.ConcurrentPublishedSnapshotException.class,
          () -> pending.commit(List.of(
              new WorkflowStateService.SnapshotGuard(source, expected))));
    }

    assertPair(page, "ru-v1\n", "en-v1\n");
  }

  @Test
  void exchangeFailurePreservesThePreviousPair() throws Exception {
    Path page = existingPair("ru-v1\n", "en-v1\n");
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        (first, second) -> { throw new IOException("exchange failed"); },
        new PublishedSnapshotStore.IoHooks() { });

    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stage(page, bytes("ru-v2\n"), bytes("en-v2\n"))) {
      IllegalStateException error =
          assertThrows(IllegalStateException.class, () -> pending.commit(List.of()));
      assertTrue(error.getMessage().contains("exchange failed"));
    }

    assertPair(page, "ru-v1\n", "en-v1\n");
  }

  @Test
  void cleanupFailureReportsTheDisplacedPairAfterCommit() throws Exception {
    Path page = existingPair("ru-v1\n", "en-v1\n");
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
        store.stage(page, bytes("ru-v2\n"), bytes("en-v2\n"))) {
      result = pending.commit(List.of());
    }

    assertPair(page, "ru-v2\n", "en-v2\n");
    assertEquals(1, result.recoveryPaths().size());
    assertTrue(Files.isDirectory(result.recoveryPaths().getFirst()));
  }

  @Test
  void rollbackFailureReportsCandidateOwnershipAndDisplacedRecoveryPath()
      throws Exception {
    Path page = existingPair("ru-v1\n", "en-v1\n");
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
        store.stage(page, bytes("ru-v2\n"), bytes("en-v2\n"))) {
      error = assertThrows(
          PublishedSnapshotStore.PublishedSnapshotRecoveryException.class,
          () -> pending.commit(List.of(
              new WorkflowStateService.SnapshotGuard(source, expected))));
    }

    assertPair(page, "ru-v2\n", "en-v2\n");
    assertEquals(
        PublishedSnapshotStore.RecoveryDisposition.CANDIDATE_VISIBLE,
        error.disposition());
    assertEquals(page.resolve("published").toAbsolutePath().normalize(),
        error.publishedPath());
    assertEquals(1, error.recoveryPaths().size());
    assertDirectoryPair(error.recoveryPaths().getFirst(), "ru-v1\n", "en-v1\n");
  }

  @Test
  void stagedCleanupFailureReportsUncommittedCandidateRecoveryPath()
      throws Exception {
    Path page = existingPair("ru-v1\n", "en-v1\n");
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void deleteTree(Path root) throws IOException {
            throw new IOException("cleanup failed");
          }
        });
    PublishedSnapshotStore.PendingSnapshot pending =
        store.stage(page, bytes("ru-v2\n"), bytes("en-v2\n"));

    PublishedSnapshotStore.PublishedSnapshotRecoveryException error =
        assertThrows(
            PublishedSnapshotStore.PublishedSnapshotRecoveryException.class,
            pending::close);

    assertPair(page, "ru-v1\n", "en-v1\n");
    assertEquals(
        PublishedSnapshotStore.RecoveryDisposition.STAGED_CANDIDATE,
        error.disposition());
    assertEquals(page.resolve("published").toAbsolutePath().normalize(),
        error.publishedPath());
    assertEquals(1, error.recoveryPaths().size());
    assertDirectoryPair(error.recoveryPaths().getFirst(), "ru-v2\n", "en-v2\n");
  }

  @Test
  void uncheckedPostVisibleFailureRollsBackBeforeClose() throws Exception {
    Path page = existingPair("ru-v1\n", "en-v1\n");
    PublishedSnapshotStore store = new PublishedSnapshotStore(
        new JnaAtomicExchange(),
        new PublishedSnapshotStore.IoHooks() {
          @Override
          public void afterVisibleCommit(Path published) {
            throw new IllegalStateException("hook failed");
          }
        });

    try (PublishedSnapshotStore.PendingSnapshot pending =
        store.stage(page, bytes("ru-v2\n"), bytes("en-v2\n"))) {
      IllegalStateException error =
          assertThrows(IllegalStateException.class, () -> pending.commit(List.of()));
      assertEquals("hook failed", error.getMessage());
    }

    assertPair(page, "ru-v1\n", "en-v1\n");
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

  private static void assertPair(Path page, String russian, String english)
      throws IOException {
    assertEquals(russian, Files.readString(page.resolve("published/ru.md")));
    assertEquals(english, Files.readString(page.resolve("published/en.md")));
  }

  private static void assertDirectoryPair(
      Path directory,
      String russian,
      String english) throws IOException {
    assertEquals(russian, Files.readString(directory.resolve("ru.md")));
    assertEquals(english, Files.readString(directory.resolve("en.md")));
  }
}
