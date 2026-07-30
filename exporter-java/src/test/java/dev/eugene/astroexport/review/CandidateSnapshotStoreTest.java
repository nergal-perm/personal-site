package dev.eugene.astroexport.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eugene.astroexport.fs.AtomicExchange;
import dev.eugene.astroexport.workflow.WorkflowStateService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CandidateSnapshotStoreTest {
  @TempDir
  Path temp;

  @Test
  void installsCandidateRuEnAndReferencesAsOneDirectorySwap() throws Exception {
    Path page = temp.resolve("review/blog/essay");
    Files.createDirectories(page);
    CandidateSnapshotStore store = new CandidateSnapshotStore();

    try (CandidateSnapshotStore.PendingCandidate pending = store.stage(
        page,
        bytes("ru"),
        bytes("en"),
        bytes("{\"schemaVersion\":1}"))) {
      pending.commit(List.of());
    }

    assertEquals(Set.of("ru.md", "en.md", "references.json"),
        leafNames(page.resolve("candidate")));
  }

  @Test
  void failedReplacementLeavesPreviousCandidateUntouched() throws Exception {
    Path page = temp.resolve("review/blog/essay");
    Files.createDirectories(page);
    CandidateSnapshotStore store = new CandidateSnapshotStore();
    try (CandidateSnapshotStore.PendingCandidate pending = store.stage(
        page, bytes("old ru"), bytes("old en"), bytes("{\"schemaVersion\":1}"))) {
      pending.commit(List.of());
    }

    try (CandidateSnapshotStore.PendingCandidate pending = store.stage(
        page, bytes("new ru"), bytes("new en"), bytes("{\"schemaVersion\":2}"))) {
      pending.commit(List.of(new WorkflowStateService.SnapshotGuard(
          page.resolve("candidate/en.md"), bytes("not the old en"))));
    } catch (CandidateSnapshotStore.ConcurrentCandidateSnapshotException expected) {
      // Expected conflict: the guard does not match the installed candidate.
    }

    assertEquals("old ru", Files.readString(page.resolve("candidate/ru.md")));
    assertEquals("old en", Files.readString(page.resolve("candidate/en.md")));
    assertEquals("{\"schemaVersion\":1}", Files.readString(page.resolve("candidate/references.json")));
  }

  @Test
  void rollbackFailureReportsVisibleCandidateRecoveryPath() throws Exception {
    Path page = temp.resolve("review/blog/essay");
    Files.createDirectories(page);
    CandidateSnapshotStore store = new CandidateSnapshotStore();
    try (CandidateSnapshotStore.PendingCandidate pending = store.stage(
        page, bytes("old ru"), bytes("old en"), bytes("{\"schemaVersion\":1}"))) {
      pending.commit(List.of());
    }
    AtomicExchange failingRollback = new AtomicExchange() {
      private int calls;

      @Override
      public void exchange(Path first, Path second) throws IOException {
        calls++;
        if (calls == 2) {
          throw new IOException("rollback unavailable");
        }
        swapDirectories(first, second);
      }
    };
    CandidateSnapshotStore recoveryStore = new CandidateSnapshotStore(failingRollback);

    CandidateSnapshotStore.CandidateSnapshotRecoveryException error = assertThrows(
        CandidateSnapshotStore.CandidateSnapshotRecoveryException.class,
        () -> {
          try (CandidateSnapshotStore.PendingCandidate pending = recoveryStore.stage(
              page, bytes("new ru"), bytes("new en"), bytes("{\"schemaVersion\":2}"))) {
            pending.commit(List.of(new WorkflowStateService.SnapshotGuard(
                page.resolve("candidate/en.md"), bytes("old en"))));
          }
        });

    assertEquals(CandidateSnapshotStore.RecoveryDisposition.CANDIDATE_VISIBLE,
        error.disposition());
    assertTrue(error.recoveryPaths().contains(page.resolve("candidate").toAbsolutePath().normalize()));
    assertEquals("new ru", Files.readString(page.resolve("candidate/ru.md")));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static Set<String> leafNames(Path directory) throws Exception {
    try (var paths = Files.list(directory)) {
      return paths
          .map(path -> path.getFileName().toString())
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
  }

  private static void swapDirectories(Path first, Path second) throws IOException {
    Path temporary = first.resolveSibling(first.getFileName() + ".swap-test");
    Files.move(first, temporary, StandardCopyOption.ATOMIC_MOVE);
    Files.move(second, first, StandardCopyOption.ATOMIC_MOVE);
    Files.move(temporary, second, StandardCopyOption.ATOMIC_MOVE);
  }
}
