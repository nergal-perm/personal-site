# S09 — Replace an Approved Snapshot Safely Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Implementer subagents run as Codex Companion Tasks (model tier per task's stated complexity); after each task, run four parallel Codex Companion review passes: spec compliance, code quality, `/applying-sbpp`, `/oo-design-heuristics`.

**Goal:** `mark-reviewed`, given a candidate that passes RVA-04's full revalidation against an *existing*
approved snapshot, replaces the prior approved RU/EN/reference-map triple atomically; a stale second approval
still blocks; an interruption during replacement recovers deterministically to exactly the old or the new
complete snapshot on the next inspection or retry; concurrent replacement attempts for the same publication
are serialized.

**Architecture:** Per `design.md` D1-D4: `ApprovedSnapshotWorkspace.install(...)` becomes replace-or-create
(drops its create-only `ApprovedSnapshotAlreadyExistsException` enforcement for the normal case, keeping the
exception only for a genuine concurrent race). `FilesystemApprovedSnapshotWorkspace` gains the same
backup-rename/move-new-in/delete-backup-on-success/restore-on-failure protocol `FilesystemCandidateWorkspace`
already proved in S08, plus durable on-disk recovery (backup-directory presence is the recovery marker,
checked at the top of `read`/`find`/`install`) since RVA-05 requires recovery to work across process restarts,
not just within one failed call. `MarkReviewedHandler` gets its own per-`PublicationIdentity`
`ReentrantLock` registry (same mechanism as `PrepareHandler`'s, separate instance) and, on finding an existing
approved snapshot, proceeds to replace instead of blocking. No new production adapter — this is entirely
inside the existing `approved`/`markreviewed` packages.

**Tech Stack:** Java 17, Maven, JUnit 5 (existing deps only — no new dependency this slice).

## Global Constraints

- Nullables: `NullApprovedSnapshotWorkspace`'s behavior changes alongside the real adapter's — both drop the
  create-only guard identically, proven via the same shared behavioral contract test style already used for
  `CandidateWorkspace`.
- No mocking libraries. State-based assertions only. Fault injection uses the same `MoveOperation`-seam
  pattern `FilesystemCandidateWorkspaceTest` already established — a package-private functional-interface
  constructor overload, not a mock.
- Outside-in TDD: one failing CLI acceptance test first, in-memory adapter wired in, then extract/harden the
  real filesystem adapter behind the same behavioral contract.
- In-memory acceptance subset stays under 1 second.
- No new production adapter — reuse `ApprovedSnapshotWorkspace`'s existing port; no new port/interface beyond
  what D1-D3 already specify as changes to the existing type.
- Every new/changed public method keeps `Objects.requireNonNull(x, "x")` guards.
- Keep classes small and single-responsibility: the backup/restore mechanics belong in
  `FilesystemApprovedSnapshotWorkspace` alone; `MarkReviewedHandler` only orchestrates revalidation + install,
  it does not know about backups or recovery.

---

## 1. `ApprovedSnapshotWorkspace` becomes replace-or-create (Null adapter + port contract)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspaceTest.java`
- Read first (no change needed if still accurate): `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/ApprovedSnapshotWorkspace.java`, `ApprovedSnapshotAlreadyExistsException.java`

**Interfaces:**
- Produces (changed behavior, same signature): `ApprovedSnapshotWorkspace#install(...)` no longer throws
  `ApprovedSnapshotAlreadyExistsException` merely because a snapshot already exists for the identity — it
  replaces it. The exception remains available for adapters to throw on a genuine same-instant race (D1).

`grep -rn "ApprovedSnapshotAlreadyExistsException\|ensureNotAlreadyInstalled" publication-exporter/src` first
— confirms every place that currently relies on create-only semantics, so nothing is missed.

- [x] 1.1 **Write the failing unit test proving replace-not-block in `NullApprovedSnapshotWorkspaceTest`**

```java
@Test
void secondInstallReplacesThePriorSnapshot() {
    NullApprovedSnapshotWorkspace workspace = new NullApprovedSnapshotWorkspace();
    workspace.install(IDENTITY, "Old RU", "Old EN", "Old RU title", "Old EN title",
            "Old RU description", "Old EN description", referenceMap("old"));

    workspace.install(IDENTITY, "New RU", "New EN", "New RU title", "New EN title",
            "New RU description", "New EN description", referenceMap("new"));

    CandidateSnapshot snapshot = workspace.read(IDENTITY).orElseThrow();
    assertEquals("New RU", snapshot.ruBody());
    assertEquals("New EN", snapshot.enBody());
}
```

Read the existing test file first to reuse its exact `IDENTITY`/`referenceMap(...)` helper names — do not
invent new ones if equivalents already exist.

- [x] 1.2 **Run it to confirm it fails** (throws `ApprovedSnapshotAlreadyExistsException` today)

Run: `cd publication-exporter && mvn -q -Dtest=NullApprovedSnapshotWorkspaceTest test`
Expected: FAILURE — `ApprovedSnapshotAlreadyExistsException` thrown instead of replacing.

- [x] 1.3 **Remove `ensureNotAlreadyInstalled(...)`'s guard from `install(...)` in `NullApprovedSnapshotWorkspace`**

```java
@Override
public void install(PublicationIdentity identity, String ruBody, String enBody,
        String ruTitle, String enTitle, String ruDescription, String enDescription, ReferenceMap referenceMap) {
    validateInstallArguments(identity, ruBody, enBody, ruTitle, enTitle,
            ruDescription, enDescription, referenceMap);
    installed.put(identity, CandidateSnapshot.of(ruBody, enBody, ruTitle, enTitle,
            ruDescription, enDescription, referenceMap));
}
```

Delete the now-unused `ensureNotAlreadyInstalled(...)` private method entirely (do not leave dead code).

- [x] 1.4 **Run the test to confirm it passes**

Run: `cd publication-exporter && mvn -q -Dtest=NullApprovedSnapshotWorkspaceTest test`
Expected: PASS.

- [x] 1.5 **Run the full suite to confirm nothing else depended on the old create-only behavior**

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS. If `MarkReviewedHandlerTest` has an existing test asserting a second `install(...)`
call blocks, it will need updating in Task 3 (not this task) — note it as a concern in your report if you
find one, do not fix it here.

- [x] 1.6 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java \
        src/test/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspaceTest.java
git commit -m "feat(approved): NullApprovedSnapshotWorkspace.install replaces instead of blocking (RVA-05)"
```

---

## 2. Failing acceptance test for `mark-reviewed` replacing an approved snapshot

**Files:**
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/MarkReviewedCliAcceptanceTest.java`
- Read first: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/MarkReviewedCommand.java`, to learn the CLI composition root's current wiring.

**Interfaces:**
- Consumes: existing test-harness helpers already in `MarkReviewedCliAcceptanceTest` (read the file first —
  do not guess method/field names).
- Produces: none (this task only adds a failing test; Task 3 makes it pass).

- [x] 2.1 **Read `MarkReviewedCliAcceptanceTest` and `MarkReviewedCommand` fully first** to learn the exact
  harness shape (how a candidate and an existing approved snapshot are seeded, how the CLI response is
  asserted) before writing a new case.

- [x] 2.2 **Write one failing acceptance test**: `secondMarkReviewedReplacesTheApprovedSnapshot()` — GIVEN an
  approved snapshot already installed for a publication, and a new candidate prepared against it that exactly
  matches its own reference-map evidence (i.e. passes RVA-04 revalidation — not stale), WHEN `mark-reviewed`
  runs again for the same note, THEN the response is `approved` (not blocked) and the approved snapshot now
  readable via `ApprovedSnapshotWorkspace` (or through whatever the test harness already exposes for
  inspection) matches the new candidate's RU/EN bytes, not the old ones.

- [x] 2.3 **Run it to confirm it fails for the right reason**

Run: `cd publication-exporter && mvn -q -Dtest=MarkReviewedCliAcceptanceTest test`
Expected: FAILURE — response is `blocked` with "replacing it is not yet supported," not `approved`. If it
fails to compile instead, fix the test code (not production code) until it compiles and fails on behavior.

- [x] 2.4 **Commit the failing test on its own**

```bash
cd publication-exporter
git add src/test/java/dev/eugene/publicationexporter/cli/MarkReviewedCliAcceptanceTest.java
git commit -m "test(mark-reviewed): add failing acceptance test for approved-snapshot replacement (S09)"
```

---

## 3. Wire replacement + per-identity lock into `MarkReviewedHandler`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java`

**Interfaces:**
- Consumes: `ApprovedSnapshotWorkspace#install(...)` (now replace-or-create per Task 1).
- Produces (changed): `MarkReviewedHandler`'s public API is unchanged (same constructor, same
  `markReviewed(...)` signature) — only its internal branching changes.

- [x] 3.1 **Read the current `alreadyApprovedResponse()` call site in `markReviewedAdmittedEssay(...)`** —
  confirm exactly where `approvedSnapshot.isPresent()` currently short-circuits to blocked, per the file
  excerpt already quoted in `design.md`'s Context section.

- [x] 3.2 **Add the per-identity lock registry and wrap the revalidate-then-install sequence**

```java
private static final ConcurrentMap<PublicationIdentity, ReentrantLock> APPROVAL_LOCKS =
        new ConcurrentHashMap<>();

private BridgeResponse markReviewedAdmittedEssay(
        PublicationIdentity identity, String sourceBody, String sourceTitle, String sourceDescription) {
    ReentrantLock lock = APPROVAL_LOCKS.computeIfAbsent(identity, ignored -> new ReentrantLock());
    lock.lock();
    try {
        return markReviewedUnderLock(identity, sourceBody, sourceTitle, sourceDescription);
    } finally {
        lock.unlock();
    }
}

private BridgeResponse markReviewedUnderLock(
        PublicationIdentity identity, String sourceBody, String sourceTitle, String sourceDescription) {
    Optional<CandidateSnapshot> candidate;
    Optional<CandidatePaths> approvedSnapshot;
    try {
        candidate = readCandidate(identity);
        if (candidate.isEmpty()) {
            return noCandidateResponse();
        }
        approvedSnapshot = findApprovedSnapshot(identity);
    } catch (LookupFailure failure) {
        return candidateLookupFailure(failure.getMessage());
    }
    List<Diagnostic> staleness = stalenessDiagnostics(
            sourceBody, sourceTitle, sourceDescription, candidate.get());
    if (!staleness.isEmpty()) {
        return BridgeResponse.stale(COMMAND, staleness);
    }
    return installApprovedSnapshot(identity, candidate.get());
}
```

Add `import java.util.concurrent.ConcurrentHashMap;`, `import java.util.concurrent.ConcurrentMap;`,
`import java.util.concurrent.locks.ReentrantLock;`. Note: `approvedSnapshot` is now looked up but its
presence no longer gates a block — it existed only to decide "block vs proceed" before; now revalidation
(RVA-04) is the only gate, applying identically whether or not a prior approved snapshot exists (per
`design.md` D4). If `approvedSnapshot` ends up completely unused after this change, remove its computation
and the now-dead `findApprovedSnapshot(...)` call/method — do not leave an unused lookup. (Check first: does
anything else need to know "was there a prior snapshot" for a diagnostic message or response field? If not,
delete it; if the response should still distinguish "approved" from "replaced" for observability, that is a
judgment call — prefer keeping `BridgeResponse.approved(...)` for both per `proposal.md`'s scope, since no
requirement asks for a distinct "replaced" status, unless removing the field breaks an existing passing test,
in which case keep it and explain why in your report.)

- [x] 3.3 **Remove `alreadyApprovedResponse()` and its call sites if no longer reachable; keep it only if the
  genuine-race exception path (Task 4) still needs a response for that case** — read `installApprovedSnapshot(...)`'s
  existing `catch (ApprovedSnapshotAlreadyExistsException raceLoser)` branch: this is the correct place for a
  "someone else's concurrent install won the race" response, which is a real and different scenario from
  "an approved snapshot already exists" (that's now normal, expected, and handled by replacing). Keep the
  exception-driven response for the race case; delete the presence-driven one.

- [x] 3.4 **Add/update `MarkReviewedHandlerTest` cases**: a replace-succeeds case (revalidated second
  approval installs the new snapshot), and confirm the existing stale-second-approval-style test (if one
  exists asserting the old "already approved" block) is either removed (if it tested the now-wrong behavior)
  or retargeted to assert staleness-blocking instead (if it happened to also have stale evidence) — read the
  existing test class fully before deciding which.

- [x] 3.5 **Run the acceptance test and full suite**

Run: `cd publication-exporter && mvn -q -Dtest=MarkReviewedHandlerTest,MarkReviewedCliAcceptanceTest test`
Expected: PASS, including Task 2's previously-failing case.

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS.

- [x] 3.6 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java \
        src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java \
        src/test/java/dev/eugene/publicationexporter/cli/MarkReviewedCliAcceptanceTest.java
git commit -m "feat(mark-reviewed): replace an existing approved snapshot when revalidation passes (RVA-05)

Serializes concurrent same-identity approvals with a per-publication lock,
mirroring PrepareHandler's S08 pattern."
```

---

## 4. Real-adapter atomic replace + durable crash recovery in `FilesystemApprovedSnapshotWorkspace`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java`
- Read first: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java`, lines covering `replaceCandidate`/`restoreBackup`/`MoveOperation` — the pattern to mirror exactly for the synchronous-failure half of this task; this task adds the durable (cross-call) recovery half on top.

**Interfaces:**
- Produces (changed): `FilesystemApprovedSnapshotWorkspace(Path reviewRoot)` gains a package-private
  `FilesystemApprovedSnapshotWorkspace(Path reviewRoot, MoveOperation moveOperation)` constructor, same shape
  as `FilesystemCandidateWorkspace`'s, for fault injection.
- Consumes: `StagedDirectoryInstall` (existing), `Files`/`UUID` (existing patterns already used in the
  sibling class).

- [x] 4.1 **Write a failing synchronous-failure test mirroring `FilesystemCandidateWorkspaceTest.failedNewMoveRestoresFullyReadableOldCandidate`**

```java
@Test
void failedNewMoveRestoresFullyReadableOldApprovedSnapshot() throws Exception {
    new FilesystemApprovedSnapshotWorkspace(reviewRoot).install(
            IDENTITY, "Old RU", "Old EN", "Old RU title", "Old EN title",
            "Old RU description", "Old EN description", referenceMap("old"));
    AtomicInteger moves = new AtomicInteger();
    FilesystemApprovedSnapshotWorkspace workspace = new FilesystemApprovedSnapshotWorkspace(reviewRoot,
            (source, target) -> {
                if (moves.incrementAndGet() == 2) {
                    throw new java.io.IOException("injected failure before new approved snapshot move");
                }
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            });

    UncheckedIOException failure = assertThrows(UncheckedIOException.class,
            () -> workspace.install(IDENTITY, "New RU", "New EN", "New RU title", "New EN title",
                    "New RU description", "New EN description", referenceMap("new")));

    assertTrue(failure.getMessage().contains("injected failure"));
    CandidateSnapshot restored = workspace.read(IDENTITY).orElseThrow();
    assertEquals("Old RU", restored.ruBody());
    assertEquals("Old EN", restored.enBody());
}
```

Adapt import names/constants to whatever `FilesystemApprovedSnapshotWorkspaceTest` already declares (its own
`IDENTITY`, `referenceMap(...)` helper, `reviewRoot` field) — read the file first.

- [x] 4.2 **Run it to confirm it fails to compile** (no `MoveOperation`-accepting constructor yet, `install(...)`
  still throws `ApprovedSnapshotAlreadyExistsException` on a second call rather than attempting a move)

Run: `cd publication-exporter && mvn -q -Dtest=FilesystemApprovedSnapshotWorkspaceTest test`
Expected: compilation FAILURE.

- [x] 4.3 **Implement the backup/restore replace protocol**, mirroring
  `FilesystemCandidateWorkspace.replaceCandidate(...)`/`restoreBackup(...)`/`moveWithinReviewRoot(...)`
  exactly (same method shapes, same `MoveOperation` interface, same `LinkOption.NOFOLLOW_LINKS`-guarded
  existence check, same `candidate-backup-<uuid>` naming convention but for `approved-backup-<uuid>`):

```java
private final MoveOperation moveOperation;

FilesystemApprovedSnapshotWorkspace(Path reviewRoot) {
    this(reviewRoot, (source, destination) ->
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE));
}

FilesystemApprovedSnapshotWorkspace(Path reviewRoot, MoveOperation moveOperation) {
    this.stagedInstall = StagedDirectoryInstall.rootedAt(Objects.requireNonNull(reviewRoot, "reviewRoot"));
    this.moveOperation = Objects.requireNonNull(moveOperation, "moveOperation");
}

@Override
public void install(PublicationIdentity identity, String ruBody, String enBody,
        String ruTitle, String enTitle, String ruDescription, String enDescription, ReferenceMap referenceMap) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(ruBody, "ruBody");
    Objects.requireNonNull(enBody, "enBody");
    Objects.requireNonNull(ruTitle, "ruTitle");
    Objects.requireNonNull(enTitle, "enTitle");
    Objects.requireNonNull(ruDescription, "ruDescription");
    Objects.requireNonNull(enDescription, "enDescription");
    Objects.requireNonNull(referenceMap, "referenceMap");

    recoverIfNeeded(identity);
    Path destination = approvedDirectory(identity);
    Path staging = createStagingDirectory();
    try {
        writeSnapshot(staging, ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap);
        requireWithinReviewRoot(destination);
        stagedInstall.createParentDirectories(destination);
        requireWithinReviewRoot(destination);
        replaceApproved(staging, destination);
    } catch (IOException error) {
        StagedDirectoryInstall.deleteRecursively(staging);
        throw new UncheckedIOException(error);
    }
}

private void replaceApproved(Path staging, Path destination) throws IOException {
    Path backup = null;
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
        backup = destination.resolveSibling("approved-backup-" + UUID.randomUUID()).normalize();
        moveWithinReviewRoot(destination, backup);
    }
    try {
        moveWithinReviewRoot(staging, destination);
    } catch (IOException installFailure) {
        restoreBackup(backup, destination, installFailure);
        throw installFailure;
    }
    if (backup != null) {
        StagedDirectoryInstall.deleteRecursively(backup);
    }
}

private void restoreBackup(Path backup, Path destination, IOException installFailure) {
    if (backup == null) {
        return;
    }
    try {
        moveWithinReviewRoot(backup, destination);
    } catch (IOException | RuntimeException restoreFailure) {
        installFailure.addSuppressed(restoreFailure);
    }
}

private void moveWithinReviewRoot(Path source, Path destination) throws IOException {
    requireWithinReviewRoot(source);
    requireWithinReviewRoot(destination);
    moveOperation.move(source, destination);
}

interface MoveOperation {
    void move(Path source, Path destination) throws IOException;
}
```

Remove the old `if (Files.exists(destination)) { throw new ApprovedSnapshotAlreadyExistsException(identity); }`
create-only guard from `install(...)` entirely — replaced by the backup/restore protocol above, which handles
both "nothing there yet" (no backup taken) and "something there" (backup then restore-on-failure) uniformly.
`recoverIfNeeded(identity)` is implemented in step 4.5 below — stub it as a no-op private method first if you
want a smaller intermediate compile step, then fill it in.

- [x] 4.4 **Run the synchronous-failure test to confirm it passes**

Run: `cd publication-exporter && mvn -q -Dtest=FilesystemApprovedSnapshotWorkspaceTest test`
Expected: PASS (the new test from 4.1, plus all pre-existing tests in this class — some existing tests may
assert the old create-only-throws behavior; update or remove those the same way Task 3.4 handled
`MarkReviewedHandlerTest`, reading each one first to decide).

- [x] 4.5 **Write a failing test for durable cross-instance recovery — restore case**, simulating a crash
  between the backup rename and the new-content move by directly constructing that on-disk state with one
  workspace instance, then using a **fresh** workspace instance (simulating a new process) to prove recovery:

```java
@Test
void freshInstanceRecoversFromInterruptedReplaceByRestoringBackup(@TempDir Path reviewRoot) throws Exception {
    FilesystemApprovedSnapshotWorkspace original = new FilesystemApprovedSnapshotWorkspace(reviewRoot);
    original.install(IDENTITY, "Old RU", "Old EN", "Old RU title", "Old EN title",
            "Old RU description", "Old EN description", referenceMap("old"));

    // Simulate a crash exactly between the backup rename and the new-content move: leave a backup
    // directory present and the canonical approved directory absent, using the same on-disk shape
    // replaceApproved(...) would leave mid-flight.
    Path approvedDir = reviewRoot.resolve(IDENTITY.publicCollection()).resolve(IDENTITY.publicId())
            .resolve("approved");
    Path backupDir = approvedDir.resolveSibling("approved-backup-" + java.util.UUID.randomUUID());
    Files.move(approvedDir, backupDir, StandardCopyOption.ATOMIC_MOVE);

    FilesystemApprovedSnapshotWorkspace freshInstance = new FilesystemApprovedSnapshotWorkspace(reviewRoot);

    CandidateSnapshot recovered = freshInstance.read(IDENTITY).orElseThrow();

    assertEquals("Old RU", recovered.ruBody());
    assertEquals("Old EN", recovered.enBody());
    assertTrue(Files.notExists(backupDir), "stale backup should be cleaned up by recovery");
}
```

Adjust the directory-path construction to match whatever private path-building logic
`FilesystemApprovedSnapshotWorkspace` actually uses (read `approvedDirectory(identity)` first) — the test
must reconstruct the exact same path the production code would use, not assume it.

- [x] 4.6 **Run it to confirm it fails** (no `recoverIfNeeded` logic yet — `read(...)` finds no approved
  directory and returns empty instead of recovering)

Run: `cd publication-exporter && mvn -q -Dtest=FilesystemApprovedSnapshotWorkspaceTest test`
Expected: FAILURE — `orElseThrow()` throws, or the assertion on `ruBody()` fails.

- [x] 4.7 **Implement `recoverIfNeeded(identity)` and call it from `read(...)`, `find(...)`, and `install(...)`**

```java
private void recoverIfNeeded(PublicationIdentity identity) {
    Path destination = approvedDirectory(identity);
    Optional<Path> backup = findBackupDirectory(destination);
    if (backup.isEmpty()) {
        return;
    }
    boolean destinationComplete = containsApprovedSnapshot(destination);
    boolean backupComplete = containsApprovedSnapshot(backup.get());
    if (destinationComplete) {
        StagedDirectoryInstall.deleteRecursively(backup.get());
        return;
    }
    if (backupComplete) {
        try {
            moveWithinReviewRoot(backup.get(), destination);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return;
    }
    throw new IllegalStateException(
            "Approved snapshot for " + identity + " is unrecoverable: neither " + destination
                    + " nor its backup " + backup.get() + " is a complete snapshot.");
}

private Optional<Path> findBackupDirectory(Path destination) {
    Path parent = destination.getParent();
    if (parent == null || !Files.isDirectory(parent)) {
        return Optional.empty();
    }
    String prefix = destination.getFileName().toString() + "-backup-";
    try (var entries = Files.list(parent)) {
        return entries.filter(Files::isDirectory)
                .filter(entry -> entry.getFileName().toString().startsWith(prefix))
                .findFirst();
    } catch (IOException error) {
        throw new UncheckedIOException(error);
    }
}
```

Call `recoverIfNeeded(identity)` as the first line of `read(...)`, `find(...)`, and `install(...)` (already
added in step 4.3's `install(...)` body above). Guard each call with `Objects.requireNonNull(identity,
"identity")` already present in those methods — `recoverIfNeeded` runs after the null check, not before.

- [x] 4.8 **Run the recovery test to confirm it passes**

Run: `cd publication-exporter && mvn -q -Dtest=FilesystemApprovedSnapshotWorkspaceTest test`
Expected: PASS.

- [x] 4.9 **Write and pass a second recovery case — cleanup case** (crash after the new-content move
  succeeded but before backup cleanup): construct on-disk state with both a complete canonical approved
  directory (the "new" content) and a complete backup directory (the "old" content) present simultaneously,
  then prove a fresh instance's `read(...)` returns the *new* content and deletes the stale backup. Follow the
  same construction style as 4.5 (direct filesystem setup, no injected failure needed — just pre-existing
  on-disk state).

- [x] 4.10 **Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS, all tests green, elapsed time still low.

- [x] 4.11 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java \
        src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java
git commit -m "feat(approved): atomic replace with durable cross-process recovery (RVA-05)

Mirrors FilesystemCandidateWorkspace's S08 backup/restore protocol, plus
recovery that runs on read/find/install so an interruption is recoverable
by a later process, not only within the failed call."
```

---

## 5. Whole-branch regression pass and requirement traceability check

**Files:** none created/modified — verification only.

- [x] 5.1 **Run the complete Maven test suite**

Run: `cd publication-exporter && mvn -B test`
Expected: `Tests run: 3XX+, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

- [x] 5.2 **Manually trace each requirement scenario to its covering test(s)**: RVA-05 "Approval completes"
  (already covered by S05's existing tests, confirm still passing) → unaffected; "Approval is interrupted" →
  Task 4's synchronous-failure and cross-instance recovery tests; "A second approval replaces the prior
  snapshot" → Task 2/3's acceptance and handler tests; RVA-04's staleness-blocks-a-second-approval case →
  confirm an existing or new `MarkReviewedHandlerTest`/`MarkReviewedCliAcceptanceTest` case covers a stale
  candidate on top of an existing approved snapshot specifically (not just a stale *first* approval) — add one
  if missing, do not silently mark this task done if it's absent.

- [x] 5.3 **Confirm `git status` is clean except for this slice's new/modified files.**

- [x] 5.4 **This task has no commit of its own** — it is the checkpoint before subagent-driven-development
  hands off to the four parallel review passes and the final whole-branch review.
