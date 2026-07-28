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
}
