# Approved Translation Published Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make successful Obsidian translation approval atomically save the current single-page Russian/English pair as the sole published comparison baseline.

**Architecture:** Add a focused pair-snapshot store that stages and swaps the whole `published/` directory, expose it through `ReviewWorkspace`, and invoke it inside the existing locked `mark-reviewed` transaction. Remove the bulk post-export snapshot so export/build/deploy cannot advance the baseline; keep the Obsidian plugin as one thin bridge call.

**Tech Stack:** Java 21, Maven, JUnit 5, JNA-backed `AtomicExchange`, picocli, Node.js built-in test runner, Obsidian desktop plugin JavaScript.

## Global Constraints

- A successful `mark-reviewed` action is the only event that advances `review/<collection>/<publicId>/published/{ru,en}.md`.
- Snapshot exactly one page, derived from the validated current `ManifestEntry`.
- Render Russian snapshot content from the stable current manifest entry; never copy mutable ordinary `review/.../ru.md`.
- Snapshot the exact English bytes committed with `translationStatus: reviewed`.
- Publish RU and EN as one pair-level filesystem commit; never expose a mixed old/new pair.
- Keep the current per-publication lock and source/English snapshot guards.
- Return `ok: true` only after the approved pair is durable.
- Keep snapshot failure retryable and preserve the previous published pair.
- Keep the Obsidian plugin to one `mark-reviewed` subprocess and no direct review-workspace writes.
- Do not build, preview, or deploy Astro during approval.
- Non-dry-run export and `build-from-review` must not create or replace `published/`.
- Existing snapshots require no migration; the next successful approval replaces them.
- Add no third-party dependencies.

---

## File map

### Create

- `exporter-java/src/main/java/dev/eugene/astroexport/review/PublishedSnapshotStore.java`
  - Owns staging, guarded pair commit, rollback, cleanup, and recovery-path reporting for one `published/` directory.
- `exporter-java/src/test/java/dev/eugene/astroexport/review/PublishedSnapshotStoreTest.java`
  - Proves first commit, replacement, rollback, cleanup recovery, and pair atomicity.

### Modify

- `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java`
  - Exposes the canonical RU renderer and the single-page approved-snapshot staging facade.
- `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java`
  - Proves renderer reuse and that snapshots do not copy mutable ordinary `ru.md`.
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java`
  - Replaces bulk snapshot injection with staged single-page snapshot injection.
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
  - Commits the staged snapshot in `markReviewed()` and removes post-export snapshotting.
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
  - Covers command success, failures, retry, concurrency, export non-ownership, and the end-to-end translation diff.
- `obsidian-plugin/main.js`
  - Reports that successful review also saved the approved baseline.
- `obsidian-plugin/tests/bridge-client.test.cjs`
  - Covers success and snapshot-failure UI behavior without adding bridge calls.
- `README.md`
  - Defines approval-owned baseline semantics in the repository pipeline.
- `exporter-java/README.md`
  - Documents `mark-reviewed`, `published/`, retry behavior, and export non-ownership.
- `e2e/README.md`
  - Clarifies that the mechanical build harness does not move the approved baseline.

---

### Task 1: Add The Pair-Atomic Published Snapshot Store

**Files:**
- Create: `exporter-java/src/main/java/dev/eugene/astroexport/review/PublishedSnapshotStore.java`
- Create: `exporter-java/src/test/java/dev/eugene/astroexport/review/PublishedSnapshotStoreTest.java`

**Interfaces:**
- Consumes: `AtomicExchange.exchange(Path first, Path second)` and `WorkflowStateService.SnapshotGuard`.
- Produces:
  - `PublishedSnapshotStore.PendingSnapshot stage(Path pageDirectory, byte[] russian, byte[] english)`
  - `PublishedSnapshotStore.CommitResult PendingSnapshot.commit(List<WorkflowStateService.SnapshotGuard> guards)`
  - `void PendingSnapshot.close()`
  - `PublishedSnapshotStore.CommitResult(List<Path> recoveryPaths)`
  - public `PublishedSnapshotStore.ConcurrentPublishedSnapshotException`

- [ ] **Step 1: Write failing store tests**

Create `PublishedSnapshotStoreTest` in package
`dev.eugene.astroexport.review`. Use `@TempDir Path temp` and add these
tests:

```java
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
```

Add exact helpers:

```java
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
```

- [ ] **Step 2: Run the new tests and verify RED**

Run:

```bash
cd exporter-java
mvn -q -Dtest=PublishedSnapshotStoreTest test
```

Expected: compilation fails because `PublishedSnapshotStore` does not exist.

- [ ] **Step 3: Implement staging and guarded pair commit**

Create this class boundary:

```java
public final class PublishedSnapshotStore {
  private final AtomicExchange atomicExchange;
  private final IoHooks ioHooks;

  PublishedSnapshotStore() {
    this(new JnaAtomicExchange(), new IoHooks() { });
  }

  PublishedSnapshotStore(AtomicExchange atomicExchange, IoHooks ioHooks) {
    this.atomicExchange = atomicExchange;
    this.ioHooks = ioHooks;
  }

  interface PendingSnapshot extends AutoCloseable {
    CommitResult commit(List<WorkflowStateService.SnapshotGuard> guards);
    @Override void close();
  }

  record CommitResult(List<Path> recoveryPaths) {
    CommitResult {
      recoveryPaths = List.copyOf(recoveryPaths);
    }
  }

  interface IoHooks {
    default void afterVisibleCommit(Path published) throws IOException { }

    default void deleteTree(Path root) throws IOException {
      PublishedSnapshotStore.deleteTree(root);
    }
  }

  public static final class ConcurrentPublishedSnapshotException
      extends IllegalStateException {
    public ConcurrentPublishedSnapshotException(String message) {
      super(message);
    }
  }
}
```

Implement `stage()` and its private `PendingSnapshot` implementation with
this exact transaction:

```text
1. Normalize pageDirectory and reject a symbolic link or non-directory.
2. Create `.published-stage-*` under pageDirectory.
3. Write only `ru.md` and `en.md`; reject null byte arrays.
4. Force each file with FileChannel.force(true).
5. On commit, validate every SnapshotGuard before changing published/.
6. If published/ is absent, atomically move staging -> published.
7. If published/ exists, reject symbolic-link/non-directory/missing-pair
   layouts, then atomicExchange.exchange(published, staging).
8. Invoke ioHooks.afterVisibleCommit(published).
9. Revalidate all guards and verify visible ru.md/en.md equal the staged
   payloads.
10. If step 9 fails, reverse the move/exchange and throw
    ConcurrentPublishedSnapshotException.
11. Mark the pending object committed.
12. Delete the displaced old directory. If deletion fails, return its path
    in CommitResult.recoveryPaths instead of rolling back the new pair.
13. close() deletes an uncommitted staging directory; after commit it leaves
    only reported recovery paths.
```

For first publication use:

```java
Files.move(
    staging,
    published,
    StandardCopyOption.ATOMIC_MOVE);
```

For replacement use:

```java
atomicExchange.exchange(published, staging);
```

Use `LinkOption.NOFOLLOW_LINKS` for every existing-leaf check. Reject any
`published/` directory containing entries other than the regular,
single-link `ru.md` and `en.md` pair. Wrap ordinary I/O failures in
`IllegalStateException("cannot commit published snapshot " + published, error)`.

- [ ] **Step 4: Run store tests and verify GREEN**

Run:

```bash
cd exporter-java
mvn -q -Dtest=PublishedSnapshotStoreTest test
```

Expected: 4 tests pass with 0 failures and 0 errors.

- [ ] **Step 5: Run filesystem regression tests**

Run:

```bash
cd exporter-java
mvn -q -Dtest=PublishedSnapshotStoreTest,JnaAtomicExchangeTest,ReviewWorkspaceTest test
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add \
  exporter-java/src/main/java/dev/eugene/astroexport/review/PublishedSnapshotStore.java \
  exporter-java/src/test/java/dev/eugene/astroexport/review/PublishedSnapshotStoreTest.java
git commit -m "feat(exporter): add atomic published pair store"
```

---

### Task 2: Expose Canonical RU Rendering And Approved Snapshot Staging

**Files:**
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java:59-67`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java:232-263`
- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java:29-42`
- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java:539-560`

**Interfaces:**
- Consumes:
  - `PublishedSnapshotStore.stage(Path, byte[], byte[])`
  - `PublishedSnapshotStore.PendingSnapshot`
  - `PublishedSnapshotStore.CommitResult`
- Produces:
  - `String ReviewWorkspace.renderRuReview(ManifestEntry entry)`
  - `ReviewWorkspace.PendingPublishedSnapshot ReviewWorkspace.stageApprovedSnapshot(Path reviewRoot, ManifestEntry entry, byte[] reviewedEnglish)`
  - `ReviewWorkspace.PublishedSnapshotResult(List<Path> recoveryPaths)`

- [ ] **Step 1: Add failing renderer and source-of-truth tests**

Add:

```java
@Test
void renderedRussianReviewExactlyMatchesTheFileWriter() throws Exception {
  ManifestEntry entry = contentEntry();
  Path written = ReviewWorkspace.writeRuReviewFile(temp.resolve("review"), entry);

  assertEquals(
      ReviewWorkspace.renderRuReview(entry),
      Files.readString(written));
}

@Test
void approvedSnapshotUsesManifestEntryInsteadOfMutableOrdinaryRuFile()
    throws Exception {
  Path review = temp.resolve("review");
  ManifestEntry entry = contentEntry();
  Path ordinary = ReviewWorkspace.writeRuReviewFile(review, entry);
  Files.writeString(ordinary, "tampered ordinary ru.md\n");
  byte[] reviewedEnglish = "reviewed English\n".getBytes(StandardCharsets.UTF_8);

  try (ReviewWorkspace.PendingPublishedSnapshot pending =
      ReviewWorkspace.stageApprovedSnapshot(review, entry, reviewedEnglish)) {
    ReviewWorkspace.PublishedSnapshotResult result = pending.commit(List.of());
    assertTrue(result.recoveryPaths().isEmpty());
  }

  assertEquals(
      ReviewWorkspace.renderRuReview(entry),
      Files.readString(review.resolve("blog/essay/published/ru.md")));
  assertEquals(
      "reviewed English\n",
      Files.readString(review.resolve("blog/essay/published/en.md")));
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
cd exporter-java
mvn -q -Dtest=ReviewWorkspaceTest#renderedRussianReviewExactlyMatchesTheFileWriter+approvedSnapshotUsesManifestEntryInsteadOfMutableOrdinaryRuFile test
```

Expected: compilation fails because the new `ReviewWorkspace` APIs do not
exist.

- [ ] **Step 3: Extract the canonical renderer**

Refactor the existing writer to:

```java
private static final PublishedSnapshotStore PUBLISHED_SNAPSHOTS =
    new PublishedSnapshotStore();

public static String renderRuReview(ManifestEntry entry) {
  Target target = target(entry);
  String markdown = target.editorial()
      ? serializeEditorial(entry)
      : serializeContent(entry);
  return clean(markdown);
}

public static Path writeRuReviewFile(Path reviewRoot, ManifestEntry entry) {
  Target target = target(entry);
  Path path =
      reviewRoot.resolve(target.collection()).resolve(target.publicId()).resolve("ru.md");
  replaceAtomically(path, renderRuReview(entry));
  return path;
}
```

Do not add a second serializer.

- [ ] **Step 4: Add the staged snapshot facade**

Add these public nested contracts:

```java
public interface PendingPublishedSnapshot extends AutoCloseable {
  PublishedSnapshotResult commit(
      List<WorkflowStateService.SnapshotGuard> guards);

  @Override
  void close();
}

public record PublishedSnapshotResult(List<Path> recoveryPaths) {
  public PublishedSnapshotResult {
    recoveryPaths = List.copyOf(recoveryPaths);
  }
}
```

Add:

```java
public static PendingPublishedSnapshot stageApprovedSnapshot(
    Path reviewRoot,
    ManifestEntry entry,
    byte[] reviewedEnglish) {
  Target target = target(entry);
  Path page =
      reviewRoot.resolve(target.collection()).resolve(target.publicId());
  PublishedSnapshotStore.PendingSnapshot pending = PUBLISHED_SNAPSHOTS.stage(
      page,
      renderRuReview(entry).getBytes(StandardCharsets.UTF_8),
      reviewedEnglish);
  return new PendingPublishedSnapshot() {
    @Override
    public PublishedSnapshotResult commit(
        List<WorkflowStateService.SnapshotGuard> guards) {
      PublishedSnapshotStore.CommitResult result = pending.commit(guards);
      return new PublishedSnapshotResult(result.recoveryPaths());
    }

    @Override
    public void close() {
      pending.close();
    }
  };
}
```

Refactor the existing test-fixture helper
`writePublishedSnapshot(Path, String, String, String, String)` to stage and
immediately commit through `PUBLISHED_SNAPSHOTS`. Preserve its current public
signature because `PrepareWorkflowTest` uses it to install fixture baselines.

- [ ] **Step 5: Run review and prepare-diff tests**

Run:

```bash
cd exporter-java
mvn -q -Dtest=ReviewWorkspaceTest,PrepareWorkflowTest#promptIncludesUnifiedDiffOfRussianSourceWhenPublishedSnapshotDiffers+sourceDiffExcludesFrontmatterReserializationNoise test
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

```bash
git add \
  exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java \
  exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java
git commit -m "feat(exporter): stage approved page snapshots"
```

---

### Task 3: Make `mark-reviewed` The Sole Baseline Commit

**Files:**
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java:40-216`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java:239-272`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java:483-502`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java:198-224`
- Modify: `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java:463-638`
- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java:170-257`
- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java:470-549`

**Interfaces:**
- Consumes:
  - `ReviewWorkspace.stageApprovedSnapshot(Path, ManifestEntry, byte[])`
  - `ReviewWorkspace.PendingPublishedSnapshot.commit(List<SnapshotGuard>)`
  - `ReviewWorkspace.PublishedSnapshotResult`
- Produces:
  - `CommandServices.stageApprovedSnapshot(Path, ManifestEntry, byte[])`
  - `CommandServices.StageApprovedSnapshotAction`
  - `CommandServices.withStageApprovedSnapshotAction(...)`
  - Bridge behavior where successful `mark-reviewed` implies a durable
    approved snapshot.

- [ ] **Step 1: Change command tests to express approval ownership**

Extend `markReviewedAtomicallyReviewsGeneratedPairAndRefreshReportsSixStateSummary`
with:

```java
Path publishedRu = review.resolve("blog/essay/published/ru.md");
Path publishedEn = review.resolve("blog/essay/published/en.md");
assertEquals(
    ReviewWorkspace.renderRuReview(currentBlogEntry(vault)),
    Files.readString(publishedRu));
assertEquals(reviewed, Files.readString(publishedEn));
```

Replace
`buildFromReviewWritesPublishedSnapshotOfRuAndEnAfterSuccessfulWrite` with:

```java
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
```

Delete
`buildFromReviewReportsCommittedWriteErrorWhenSnapshotPublishedFails`; that
injection point no longer exists.

- [ ] **Step 2: Add failing snapshot failure, retry, and concurrency tests**

Add a test-only pending snapshot implementation through the new service
injection:

```java
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
```

Add:

```java
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
  assertEquals("old ru\n",
      Files.readString(review.resolve("blog/essay/published/ru.md")));
  assertEquals("old en\n",
      Files.readString(review.resolve("blog/essay/published/en.md")));
}
```

Add these exact test helpers:

```java
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

@SuppressWarnings("unchecked")
private static String firstDiagnosticField(Map<String, Object> payload) {
  List<Map<String, Object>> diagnostics =
      (List<Map<String, Object>>) payload.get("diagnostics");
  return String.valueOf(diagnostics.getFirst().get("field"));
}
```

- [ ] **Step 3: Run command tests and verify RED**

Run:

```bash
cd exporter-java
mvn -q -Dtest=AstroExportCommandTest#markReviewedAtomicallyReviewsGeneratedPairAndRefreshReportsSixStateSummary+buildFromReviewDoesNotCreateOrReplaceApprovedSnapshot+snapshotFailureReturnsReadyToPublishAndRetryCompletesTheBaseline+sourceChangeAtPublishedCommitReturnsStaleAndPreservesOldPair test
```

Expected: compilation or assertion failures because approval does not yet
stage/commit snapshots and export still owns the baseline.

- [ ] **Step 4: Replace the service injection boundary**

In `CommandServices` replace:

```java
private final SnapshotPublishedAction snapshotPublishedAction;
```

with:

```java
private final StageApprovedSnapshotAction stageApprovedSnapshotAction;
```

Use `ReviewWorkspace::stageApprovedSnapshot` in `defaults()`. Forward the new
field through every constructor/copy method. Replace
`withSnapshotPublishedAction` with:

```java
public CommandServices withStageApprovedSnapshotAction(
    StageApprovedSnapshotAction replacement) {
  return new CommandServices(
      clock,
      selectionAction,
      manifestAction,
      englishManifestAction,
      prepareAction,
      writeSiteAction,
      gateRunner,
      workflowState,
      publicationValidator,
      preflightService,
      preflightObserver,
      migrateOverridesAction,
      writeRuReviewAction,
      replaceEnglishReviewAction,
      replacement);
}
```

Add:

```java
public ReviewWorkspace.PendingPublishedSnapshot stageApprovedSnapshot(
    Path reviewRoot,
    ManifestEntry entry,
    byte[] reviewedEnglish) {
  return stageApprovedSnapshotAction.stage(
      reviewRoot, entry, reviewedEnglish);
}

@FunctionalInterface
public interface StageApprovedSnapshotAction {
  ReviewWorkspace.PendingPublishedSnapshot stage(
      Path reviewRoot,
      ManifestEntry entry,
      byte[] reviewedEnglish);
}
```

Remove `SnapshotPublishedAction` and `snapshotPublished(...)`.

- [ ] **Step 5: Remove post-export baseline advancement**

Delete the entire `services.snapshotPublished(reviewRoot, manifest)` try/catch
block after `writeSite()` returns in `AstroExportCommand.runExport`.
The write-report path must follow `writeSite()` directly.

Delete `ReviewWorkspace.snapshotPublished(Path, ManifestResult)` after all
callers are removed. Keep the single-pair `writePublishedSnapshot(...)`
fixture API.

- [ ] **Step 6: Stage and commit the pair inside `markReviewed()`**

Import:

```java
import dev.eugene.astroexport.review.PublishedSnapshotStore;
```

Immediately after computing `reviewedBytes`, stage the exact pair:

```java
ReviewWorkspace.PendingPublishedSnapshot pendingSnapshot;
try {
  pendingSnapshot = services.stageApprovedSnapshot(
      reviewRoot, stable.preflight().entry(), reviewedBytes);
} catch (RuntimeException error) {
  emitJson(bridge("mark-reviewed", false,
          freshPairWorkflowStatus(pair.translationStatus()))
      .note(note)
      .identity(identity)
      .diagnostics(List.of(new PublicationDiagnostic(
          "published-snapshot",
          "Could not stage the approved publication baseline ("
              + error.getClass().getSimpleName()
              + "); the previous baseline was not changed.")))
      .workspaceHealth(stable.preflight().workspaceHealth())
      .pairFreshness("fresh")
      .translationStatus(pair.translationStatus())
      .build());
  return 1;
}
```

Wrap the existing guarded English replacement, guarded
`ready_to_publish` source update, and the final snapshot commit below in one
`try (pendingSnapshot) { ... }` scope. This guarantees that every failure
before `commit()` removes the hidden staged directory.

After `setWorkflowIfChanged(...)`, perform final validation:

```java
StablePreflight approved = stablePreflight(
    vaultRoot, note, current.preflight().note().path());
String stagedHash = requiredTranslationSourceHash(stable.preflight().entry());
String approvedHash =
    requiredTranslationSourceHash(approved.preflight().entry());
if (!approved.preflight().ready()
    || !identity.samePublicIdentity(
        identityFromPreflight(approved.preflight(), reviewRoot))
    || !stagedHash.equals(approvedHash)
    || !Arrays.equals(readSafeRegularFile(english), reviewedBytes)) {
  throw new WorkflowStateService.ConcurrentFileUpdateException(
      "source projection or English review changed before published snapshot commit");
}

ReviewWorkspace.PublishedSnapshotResult published =
    pendingSnapshot.commit(List.of(
        new WorkflowStateService.SnapshotGuard(
            approved.preflight().note().path(),
            approved.sourceSnapshot()),
        new WorkflowStateService.SnapshotGuard(english, reviewedBytes)));
```

Convert `published.recoveryPaths()` into non-blocking diagnostics:

```java
List<PublicationDiagnostic> snapshotDiagnostics =
    published.recoveryPaths().stream()
        .map(path -> new PublicationDiagnostic(
            "published-snapshot-cleanup",
            "Approved baseline was saved, but the displaced previous snapshot "
                + "could not be removed; recovery path: " + path,
            false))
        .toList();
```

Attach `snapshotDiagnostics` to the successful bridge response.

Catch
`PublishedSnapshotStore.ConcurrentPublishedSnapshotException` as `stale`,
preserving the previous pair. Catch any other snapshot commit failure after
source/English approval with:

```java
emitJson(bridge("mark-reviewed", false, "ready_to_publish")
    .note(note)
    .identity(identity)
    .diagnostics(List.of(new PublicationDiagnostic(
        "published-snapshot",
        "English review is approved, but the published baseline was not "
            + "updated (" + error.getClass().getSimpleName()
            + "); invoke Mark current translation reviewed again.")))
    .workspaceHealth(stable.preflight().workspaceHealth())
    .pairFreshness("fresh")
    .translationStatus("reviewed")
    .build());
return 1;
```

Ensure the retry path stages and commits even when `en.md` is already
`reviewed` and source workflow fields are already `ready_to_publish`.

- [ ] **Step 7: Run focused command tests and verify GREEN**

Run the Step 3 command again.

Expected: all 4 tests pass.

- [ ] **Step 8: Run the complete CLI/review regression slice**

Run:

```bash
cd exporter-java
mvn -q -Dtest=AstroExportCommandTest,ReviewWorkspaceTest,PublishedSnapshotStoreTest,WorkflowStateServiceTest test
```

Expected: all selected tests pass.

- [ ] **Step 9: Commit**

```bash
git add \
  exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java \
  exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java \
  exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java \
  exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java
git commit -m "feat(exporter): snapshot pair on translation approval"
```

---

### Task 4: Prove The Approved Baseline Drives The Next Translation Diff

**Files:**
- Modify: `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`

**Interfaces:**
- Consumes:
  - `astro-export mark-reviewed`
  - `astro-export prepare`
  - `PrepareWorkflow.TranslationRunner`
  - `ReviewWorkspace.readPublishedRu(...)`
- Produces: one command-level regression test covering approval, a small RU
  edit, and the next agent prompt.

- [ ] **Step 1: Add the failing end-to-end command test**

Add:

```java
@Test
void approvedRussianSnapshotScopesTheNextPreparePromptToTheSmallEdit()
    throws Exception {
  Path vault = temp.resolve("vault");
  Path source = writeBlogNote(
      vault,
      "Paragraph one.\n\nParagraph two.\n\nParagraph three.");
  Path review = temp.resolve("review");
  Path jobs = temp.resolve("jobs");
  ManifestEntry versionOne = currentBlogEntry(vault);
  writeBlogReviewEn(review, versionOne.translationSourceHash(), "generated");

  CommandFixture.Result approved = runMarkReviewed(
      command(), vault, review, jobs);
  assertEquals(0, approved.exitCode(), approved.stderr());

  Files.writeString(
      source,
      Files.readString(source)
          .replace("Paragraph two.", "Paragraph two edited."));

  String[] prompt = {null};
  PrepareWorkflow.TranslationRunner runner = (workdir, instructions, timeout) -> {
    prompt[0] = instructions;
    Map<String, Object> journal = JSON.readValue(
        Files.readString(workdir.resolve("job.json")),
        new TypeReference<LinkedHashMap<String, Object>>() { });
    Files.writeString(workdir.resolve("candidate.en.md"), """
        ---
        sourceHash: %s
        translationStatus: generated
        translatedAt: 2026-07-28
        translationProfile: fake-codex-v1
        title: English title
        description: English description.
        ---
        English paragraph one.

        English paragraph two edited.

        English paragraph three.
        """.formatted(journal.get("sourceHash")));
    return new CodexRunner.Run(0, "", "", false);
  };
  CommandServices services = CommandServices.defaults()
      .withPrepareAction((actualVault, note, actualReview, actualJobs, resolver) ->
          new PrepareWorkflow(
              runner,
              Clock.fixed(
                  Instant.parse("2026-07-28T12:00:00Z"),
                  ZoneOffset.UTC))
              .prepare(actualVault, note, actualReview, actualJobs));

  CommandFixture.Result prepared = run(
      new AstroExportCommand(services),
      "prepare",
      "--vault", vault.toString(),
      "--note", "anywhere/Essay.md",
      "--review", review.toString(),
      "--jobs", jobs.toString(),
      "--json");

  assertEquals(0, prepared.exitCode(), prepared.stderr());
  assertTrue(prompt[0].contains("<source-diff>"));
  assertTrue(prompt[0].contains("-Paragraph two."));
  assertTrue(prompt[0].contains("+Paragraph two edited."));
  assertFalse(prompt[0].contains("-Paragraph one."));
  assertFalse(prompt[0].contains("+Paragraph one."));
  assertFalse(prompt[0].contains("-Paragraph three."));
  assertFalse(prompt[0].contains("+Paragraph three."));
}
```

Import `dev.eugene.astroexport.process.CodexRunner`.

- [ ] **Step 2: Run the regression test**

Run:

```bash
cd exporter-java
mvn -q -Dtest=AstroExportCommandTest#approvedRussianSnapshotScopesTheNextPreparePromptToTheSmallEdit test
```

Expected before Task 3 is complete: FAIL because approval does not create the
baseline. Expected after Task 3: PASS.

- [ ] **Step 3: Inspect the generated prompt assertion boundary**

Read the failed/passed assertion output and confirm that only diff-marker
lines (`+` and `-`) are constrained. Unified-diff context may legitimately
contain unchanged paragraphs, so do not assert that unchanged text is absent
from the whole prompt.

- [ ] **Step 4: Run the full command and prepare suites**

Run:

```bash
cd exporter-java
mvn -q -Dtest=AstroExportCommandTest,PrepareWorkflowTest test
```

Expected: both test classes pass with 0 failures and 0 errors.

- [ ] **Step 5: Commit**

```bash
git add exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java
git commit -m "test(exporter): prove approval-scoped translation diff"
```

---

### Task 5: Update The Obsidian Approval Feedback

**Files:**
- Modify: `obsidian-plugin/main.js:486-501`
- Modify: `obsidian-plugin/tests/bridge-client.test.cjs:625-701`

**Interfaces:**
- Consumes: the unchanged single `bridgeClient.run("mark-reviewed", file.path)` call.
- Produces:
  - Success notice: `Перевод проверен; одобренная версия сохранена.`
  - Existing diagnostics modal for `ok: false`, including
    `published-snapshot` failures.

- [ ] **Step 1: Add failing plugin tests**

Add:

```javascript
test("successful review reports the saved approved version with one bridge call", async () => {
  const harness = loadPluginHarness();
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();
  harness.app.workspace.activeFile = new harness.FakeTFile("concepts/Current.md");
  const bridgeCalls = [];
  plugin.bridgeClient = {
    async run(...args) {
      bridgeCalls.push(args);
      return response("mark-reviewed", {
        status: "ready_to_publish",
        translationStatus: "reviewed",
      });
    },
  };

  await command(plugin, "mark-current-translation-reviewed").callback();

  assert.deepEqual(bridgeCalls, [["mark-reviewed", "concepts/Current.md"]]);
  assert.ok(harness.notices.some(
    ({ message }) => message === "Перевод проверен; одобренная версия сохранена.",
  ));
});

test("published snapshot failure shows diagnostics without approval success", async () => {
  const harness = loadPluginHarness();
  const plugin = new harness.PluginClass(harness.app);
  await plugin.onload();
  harness.app.workspace.activeFile = new harness.FakeTFile("concepts/Current.md");
  plugin.bridgeClient = {
    async run() {
      return response("mark-reviewed", {
        ok: false,
        status: "ready_to_publish",
        translationStatus: "reviewed",
        diagnostics: [{
          field: "published-snapshot",
          message: "Approved baseline was not updated; retry.",
          blocking: true,
        }],
      });
    },
  };

  await command(plugin, "mark-current-translation-reviewed").callback();

  assert.match(harness.modals.at(-1).contentEl.text(), /published-snapshot/);
  assert.equal(
    harness.notices.some(
      ({ message }) => message ===
        "Перевод проверен; одобренная версия сохранена.",
    ),
    false,
  );
});
```

- [ ] **Step 2: Run plugin tests and verify RED**

Run:

```bash
node --test obsidian-plugin/tests/bridge-client.test.cjs
```

Expected: the success-message test fails because the old notice says only
that the translation was reviewed.

- [ ] **Step 3: Change only the successful notice**

In `markCurrentReviewed()`, replace:

```javascript
new Notice("Перевод отмечен как проверенный.");
```

with:

```javascript
new Notice("Перевод проверен; одобренная версия сохранена.");
```

Do not change `bridge-client.js`, the inlined bridge command table, command
registration, subprocess arguments, or JSON schema.

- [ ] **Step 4: Run plugin tests and verify GREEN**

Run:

```bash
node --test obsidian-plugin/tests/bridge-client.test.cjs
```

Expected: all plugin tests pass.

- [ ] **Step 5: Commit**

```bash
git add obsidian-plugin/main.js obsidian-plugin/tests/bridge-client.test.cjs
git commit -m "feat(obsidian-plugin): report saved approved baseline"
```

---

### Task 6: Document Semantics And Run Full Verification

**Files:**
- Modify: `README.md:19-30`
- Modify: `exporter-java/README.md`
- Modify: `e2e/README.md:10-28`

**Interfaces:**
- Consumes: completed Java and plugin behavior.
- Produces: operator documentation and fresh full-suite/native evidence.

- [ ] **Step 1: Update repository pipeline documentation**

Change the pipeline description to state:

```markdown
3. **exporter-java** validates the pair. Successful **Mark current translation
   reviewed** stores the exact approved page pair at
   `review/<collection>/<publicId>/published/{ru,en}.md`.
4. Later Russian edits are diffed against that approved Russian snapshot when
   the next translation draft is prepared.
5. `build-from-review`, `npm run build`, preview, and deployment consume
   reviewed content but never advance the approved baseline.
```

Keep the existing manual deployment statement.

- [ ] **Step 2: Add the exporter operator contract**

Add an `## Approved translation baseline` section to
`exporter-java/README.md` containing:

```markdown
## Approved translation baseline

`astro-export mark-reviewed` is the only command that advances:

`review/<collection>/<publicId>/published/{ru.md,en.md}`

The command saves one validated page pair after English review approval and
returns success only after the pair is durable. `prepare` uses the Russian
snapshot for its next source diff. Export, `build-from-review`, Astro build,
preview, and deployment never change this baseline.

If approval reports `published-snapshot`, the English/source approval may
already be durable but the prior baseline was preserved. Run **Mark current
translation reviewed** again; retry is idempotent.
```

- [ ] **Step 3: Clarify the end-to-end harness boundary**

Add this paragraph to `e2e/README.md`:

```markdown
The harness deliberately does not advance `published/`. That baseline belongs
only to the human approval action in Obsidian/`mark-reviewed`; the harness
tests later mechanical export and site-build consumption.
```

- [ ] **Step 4: Run whitespace and placeholder checks**

Run:

```bash
git diff --check
rg -n "T[B]D|T[O]DO|F[I]XME|implement l[a]ter|fill in" \
  README.md exporter-java/README.md e2e/README.md \
  exporter-java/src/main/java \
  exporter-java/src/test/java \
  obsidian-plugin
```

Expected: `git diff --check` exits 0. The `rg` command finds no newly added
placeholder text; pre-existing matches, if any, must be listed and shown
unrelated before continuing.

- [ ] **Step 5: Run all JVM tests**

Run:

```bash
cd exporter-java
mvn test
```

Expected: Maven exits 0 with 0 failures and 0 errors. Record the test count
from Surefire output.

- [ ] **Step 6: Run all Obsidian plugin tests**

Run:

```bash
node --test obsidian-plugin/tests/bridge-client.test.cjs
```

Expected: Node exits 0 and reports 0 failed tests.

- [ ] **Step 7: Build and smoke-test the native exporter**

Run:

```bash
cd exporter-java
mvn -Pnative native:compile
target/astro-export --help
mvn -q -Dtest=NativeCliParityTest test
```

Expected: native compilation exits 0, `--help` lists `mark-reviewed`, and
`NativeCliParityTest` passes.

- [ ] **Step 8: Run shell syntax checks**

Run:

```bash
bash -n \
  exporter-java/scripts/export-site.sh \
  exporter-java/scripts/build-from-review.sh \
  exporter-java/scripts/build-astro-site.sh \
  exporter-java/scripts/migrate-overrides.sh \
  e2e/run.sh
```

Expected: exit 0 with no output.

- [ ] **Step 9: Review the final diff against the approved design**

Run:

```bash
git diff --stat c9d7658
git diff c9d7658 -- \
  README.md \
  exporter-java \
  obsidian-plugin \
  e2e/README.md
```

Confirm:

```text
- only mark-reviewed calls stageApprovedSnapshot
- runExport has no published-snapshot write
- plugin issues one mark-reviewed call
- RU snapshot rendering comes from ManifestEntry
- EN snapshot bytes are the committed reviewed bytes
- pair swap and rollback tests exist
- approval -> small RU edit -> prepare diff test exists
```

- [ ] **Step 10: Commit documentation**

```bash
git add README.md exporter-java/README.md e2e/README.md
git commit -m "docs: define approval-owned translation baseline"
```

- [ ] **Step 11: Verify the committed tree is clean**

Run:

```bash
git status --short
git log -8 --oneline
```

Expected: empty status and one focused commit for each completed task,
following the design/plan commits.
