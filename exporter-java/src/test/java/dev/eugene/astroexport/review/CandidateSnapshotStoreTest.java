package dev.eugene.astroexport.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eugene.astroexport.workflow.WorkflowStateService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
