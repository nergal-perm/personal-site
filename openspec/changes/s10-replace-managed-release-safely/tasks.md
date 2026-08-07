# S10 — Replace a Managed Release Safely Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Implementer subagents run as Codex Companion Tasks (model tier per task's stated complexity); after each task, run four parallel Codex Companion review passes: spec compliance, code quality, `/applying-sbpp`, `/oo-design-heuristics`.

**Goal:** `install-to-site`, given a newly approved snapshot and an already-installed managed generation,
replaces the prior managed RU/EN markdown files and provenance record atomically as one coherent generation;
input drift between planning and commit blocks the release with live trees untouched; an interruption
recovers deterministically (always to the old generation, per `design.md` D2) on the next retry; concurrent
replacement attempts for the same identity are serialized via a real cross-process lock.

**Architecture:** Per `design.md` D1-D4: `ManagedSiteInstaller.install(...)` becomes replace-or-create.
`FilesystemManagedSiteInstaller` gains a per-file (not per-directory — a managed generation is two separate
locale files, not one directory) backup-rename/move-new-in/delete-backup-on-success/restore-on-failure
protocol for `ru.md`/`en.md`, with provenance written last and recomputed fresh after any rollback. Durable
recovery (`recoverIfNeeded`) always rolls back to the old generation when a leftover backup is found — no
forward-completion branch needed, unlike S09, because provenance is the completion marker and it's written
strictly after both locale files succeed. The existing `Files.createFile(...)`-based install lock is replaced
with `FileChannel.tryLock()`, exactly mirroring S09's final, `SIGKILL`-probe-verified design. `InstallToSiteHandler`
recaptures and re-verifies the approved snapshot's hashes immediately before commit (input-drift guard, D4).
No new production adapter — this is entirely inside the existing `site`/`installtosite` packages.

**Tech Stack:** Java 17, Maven, JUnit 5 (existing deps only — no new dependency this slice).

## Global Constraints

- Nullables: `NullManagedSiteInstaller`'s behavior changes alongside the real adapter's — both drop the
  create-only guard identically.
- No mocking libraries. State-based assertions only. Fault injection uses the same seam pattern
  (`FilesystemCandidateWorkspaceTest`'s `MoveOperation`, `FilesystemApprovedSnapshotWorkspaceTest`'s fault
  seams) already established in this codebase.
- Outside-in TDD: one failing CLI acceptance test first, in-memory adapter wired in, then extract/harden the
  real filesystem adapter behind the same behavioral contract — the plan's own required acceptance boundary:
  exercise two release generations through the in-memory adapter, then run the shared contract against the
  filesystem adapter.
- In-memory acceptance subset stays under 1 second.
- No new production adapter — reuse `ManagedSiteInstaller`'s existing port; no new interface beyond what
  D1-D4 already specify as changes to the existing type.
- Every new/changed public method keeps `Objects.requireNonNull(x, "x")` guards.
- Do NOT reintroduce the `Files.createFile(...)`+PID/reclaim lock family — use `FileChannel.tryLock()`
  directly from the start (D3); that design space is already closed, re-litigating it here would repeat
  S09's four review rounds for no reason.
- Keep classes small and single-responsibility: backup/restore/recovery mechanics belong in
  `FilesystemManagedSiteInstaller` alone; `InstallToSiteHandler` only orchestrates the input-drift recheck
  and install call, it does not know about backups or recovery.

---

## 1. `ManagedSiteInstaller` becomes replace-or-create (Null adapter + port contract)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/NullManagedSiteInstaller.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/NullManagedSiteInstallerTest.java`
- Read first (no change needed if still accurate): `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/ManagedSiteInstaller.java`, `SiteAlreadyInstalledException.java`

**Interfaces:**
- Produces (changed behavior, same signature): `ManagedSiteInstaller#install(...)` no longer throws
  `SiteAlreadyInstalledException` merely because a generation already exists for the identity — it replaces
  it. The exception remains available for a genuine concurrent race (lock collision).

`grep -rn "SiteAlreadyInstalledException" publication-exporter/src` first — confirms every place currently
relying on create-only semantics.

- [ ] 1.1 **Write the failing unit test proving replace-not-block in `NullManagedSiteInstallerTest`**

```java
@Test
void secondInstallReplacesThePriorGeneration() {
    NullManagedSiteInstaller installer = new NullManagedSiteInstaller();
    installer.install(IDENTITY, candidateSnapshot("Old RU", "Old EN"));

    installer.install(IDENTITY, candidateSnapshot("New RU", "New EN"));

    CandidateSnapshot installed = installer.installed().get(IDENTITY);
    assertEquals("New RU", installed.ruBody());
    assertEquals("New EN", installed.enBody());
}
```

Read the existing test file first to reuse its exact `IDENTITY`/`candidateSnapshot(...)`-equivalent helper
names — do not invent new ones if equivalents already exist.

- [ ] 1.2 **Run it to confirm it fails** (throws `SiteAlreadyInstalledException` today)

Run: `cd publication-exporter && mvn -q -Dtest=NullManagedSiteInstallerTest test`
Expected: FAILURE.

- [ ] 1.3 **Remove the create-only guard from `NullManagedSiteInstaller.install(...)`**

```java
@Override
public void install(PublicationIdentity identity, CandidateSnapshot approvedSnapshot) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(approvedSnapshot, "approvedSnapshot");
    installed.put(identity, approvedSnapshot);
}
```

- [ ] 1.4 **Run the test to confirm it passes**

Run: `cd publication-exporter && mvn -q -Dtest=NullManagedSiteInstallerTest test`
Expected: PASS.

- [ ] 1.5 **Run the full suite to confirm nothing else depended on the old create-only behavior**

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS. If `InstallToSiteHandlerTest` has an existing test asserting a second `install(...)`
call blocks, it will need updating in Task 3 (not this task) — note it as a concern in your report if found,
do not fix it here.

- [ ] 1.6 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/site/NullManagedSiteInstaller.java \
        src/test/java/dev/eugene/publicationexporter/site/NullManagedSiteInstallerTest.java
git commit -m "feat(site): NullManagedSiteInstaller.install replaces instead of blocking (REL-05)"
```

---

## 2. Failing acceptance test for `install-to-site` replacing a managed generation

**Files:**
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InstallToSiteCliAcceptanceTest.java`
- Read first: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/InstallToSiteCommand.java`, to learn the CLI composition root's current wiring.

**Interfaces:**
- Consumes: existing test-harness helpers already in `InstallToSiteCliAcceptanceTest` (read the file first —
  do not guess method/field names).
- Produces: none (this task only adds a failing test; Tasks 3-4 make it pass).

- [ ] 2.1 **Read `InstallToSiteCliAcceptanceTest` and `InstallToSiteCommand` fully first** to learn the exact
  harness shape (how an approved snapshot is seeded, how a first install is performed, how the CLI response
  and installed managed files are asserted) before writing a new case.

- [ ] 2.2 **Write one failing acceptance test**: `secondInstallToSiteReplacesTheManagedGeneration()` — GIVEN a
  publication already installed to the managed site (via the existing first-install flow this test class
  already proves), and a new approved snapshot (seed via `ApprovedSnapshotWorkspace` directly, or via the
  full prepare→mark-reviewed flow if that's the existing harness's pattern — read it first) with different
  RU/EN content, WHEN `install-to-site` runs again for the same note, THEN the response is `installed` (not
  blocked) and the managed markdown files on disk now contain the new content, not the old.

- [ ] 2.3 **Run it to confirm it fails for the right reason**

Run: `cd publication-exporter && mvn -q -Dtest=InstallToSiteCliAcceptanceTest test`
Expected: FAILURE — response is `blocked` with "replacing it is not yet supported," not `installed`. If it
fails to compile instead, fix the test code (not production code) until it compiles and fails on behavior.

- [ ] 2.4 **Commit the failing test on its own**

```bash
cd publication-exporter
git add src/test/java/dev/eugene/publicationexporter/cli/InstallToSiteCliAcceptanceTest.java
git commit -m "test(install-to-site): add failing acceptance test for managed-release replacement (S10)"
```

---

## 3. Wire replacement + input-drift guard into `InstallToSiteHandler`

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandler.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandlerTest.java`

**Interfaces:**
- Consumes: `ManagedSiteInstaller#install(...)` (now replace-or-create per Task 1).
- Produces (changed): `InstallToSiteHandler`'s public API is unchanged (same constructor, same
  `installToSite(...)` signature) — only its internal branching changes.

- [ ] 3.1 **Read the current `"replacing it is not yet supported"` call site in `installApprovedSnapshot(...)`**
  — confirm exactly where `SiteAlreadyInstalledException` currently short-circuits to blocked, per the file
  excerpt already quoted in `design.md`'s Context section.

- [ ] 3.2 **Add the input-drift recheck (D4) immediately before calling `managedSiteInstaller.install(...)`**

```java
private InstallToSiteResult installApprovedSnapshot(PublicationIdentity identity, CandidateSnapshot planned) {
    Optional<CandidateSnapshot> current;
    try {
        current = approvedSnapshotWorkspace.read(identity);
    } catch (UncheckedIOException failure) {
        return InstallToSiteResult.blocked(IoFailureMessages.describe("Approved snapshot lookup failed", failure));
    } catch (ApprovedSnapshotWorkspaceConfinementException failure) {
        return InstallToSiteResult.blocked("Approved snapshot lookup failed: " + failure.getMessage());
    } catch (ApprovedSnapshotWorkspaceStateException failure) {
        return InstallToSiteResult.blocked("Approved snapshot lookup failed: " + failure.getMessage());
    }
    if (current.isEmpty() || !sameApprovedContent(planned, current.get())) {
        return InstallToSiteResult.blocked(
                "Approved snapshot changed since release was planned; site installation was not attempted.");
    }
    try {
        managedSiteInstaller.install(identity, planned);
    } catch (SiteAlreadyInstalledException raceLoser) {
        return InstallToSiteResult.blocked(
                "Another site installation is already in progress for this publication.");
    } catch (UncheckedIOException failure) {
        return InstallToSiteResult.blocked(IoFailureMessages.describe("Site installation failed", failure));
    } catch (UnsafeManagedSiteEntryException failure) {
        return InstallToSiteResult.blocked(
                "Site installation refused unsafe managed content: " + failure.getMessage());
    }
    return InstallToSiteResult.installed(identity);
}

private static boolean sameApprovedContent(CandidateSnapshot planned, CandidateSnapshot current) {
    return planned.referenceMap().ruHash().equals(current.referenceMap().ruHash())
            && planned.referenceMap().enHash().equals(current.referenceMap().enHash())
            && planned.referenceMap().ruTitleHash().equals(current.referenceMap().ruTitleHash())
            && planned.referenceMap().enTitleHash().equals(current.referenceMap().enTitleHash())
            && planned.referenceMap().ruDescriptionHash().equals(current.referenceMap().ruDescriptionHash())
            && planned.referenceMap().enDescriptionHash().equals(current.referenceMap().enDescriptionHash());
}
```

Note: `SiteAlreadyInstalledException`'s message changes meaning here — it now means "a concurrent
installer holds the lock," not "already installed, unsupported." Update the diagnostic text accordingly (as
shown above) so operators aren't told replacement is unsupported when it now is.

- [ ] 3.3 **Add/update `InstallToSiteHandlerTest` cases**: a replace-succeeds case (unchanged approved
  snapshot between plan and commit installs the new generation), and an input-drift-blocks case (approved
  snapshot changes between the handler's read and the simulated commit — use the existing
  `ApprovedSnapshotWorkspace` fake's `install(...)` to mutate between the handler's two reads, or whatever
  seam the existing test class already has for controlling read timing; read the file first). Confirm any
  existing test asserting the old "already installed, unsupported" block is removed or retargeted to the
  input-drift-blocks case if it happens to also fit that shape — read the existing test class fully before
  deciding which.

- [ ] 3.4 **Run the acceptance test and full suite**

Run: `cd publication-exporter && mvn -q -Dtest=InstallToSiteHandlerTest,InstallToSiteCliAcceptanceTest test`
Expected: `InstallToSiteHandlerTest` PASS. The CLI acceptance test from Task 2 will likely still be RED here
— it exercises the real filesystem adapter, which Task 4 makes replace-capable. This mirrors the exact
Task 3/Task 4 split S09 used; do not treat this as a Task 3 defect (note it in your report).

Run: `cd publication-exporter && mvn -q test`
Expected: all tests green except the one Task 2 CLI acceptance test, which is expected to remain red.

- [ ] 3.5 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandler.java \
        src/test/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandlerTest.java
git commit -m "feat(install-to-site): replace an existing managed generation with an input-drift guard (REL-04, REL-05)"
```

---

## 4. Real-adapter atomic per-file replace + durable recovery + real cross-process lock

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstaller.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstallerTest.java`
- Read first: `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java` (per-file backup/restore idiom) and
  `publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java`
  (its FINAL `withApprovalLock`/`FileChannel.tryLock()` shape — read the CURRENT file, not an earlier
  version; it went through several revisions during S09 and only the final `FileChannel`-based design is
  correct — mirror that one exactly, not any `Files.createFile`/PID-based intermediate).

**Interfaces:**
- Produces (changed): `install(...)` becomes replace-or-create using the per-file backup/restore protocol
  from `design.md` D1, with `recoverIfNeeded(identity)` (D2) run first, and the install lock replaced by
  `FileChannel.tryLock()` (D3).

- [ ] 4.1 **Write a failing test proving replacement + durable recovery for a single locale file, mirroring
  `FilesystemApprovedSnapshotWorkspaceTest`'s pattern**: construct a first installed generation, then directly
  simulate an interrupted replace by renaming `ru.md` to `ru.md.backup-<uuid>` and NOT completing the swap
  (leave `ru.md` absent, matching what `recoverIfNeeded` would find mid-replace), then prove a fresh
  `FilesystemManagedSiteInstaller` instance's next `install(...)` call recovers by restoring `ru.md` from the
  backup before proceeding with its own new replace attempt (or, if you construct the test to call `install`
  with content identical to the backup, simply prove the backup is gone and `ru.md` holds the recovered
  content). Follow this codebase's `@TempDir`/real-filesystem test style — no mocking.

- [ ] 4.2 **Run it to confirm it fails** (no per-file backup/restore or recovery logic yet — the old code
  either throws `SiteAlreadyInstalledException` on the second `install(...)` or leaves the simulated backup
  file untouched)

Run: `cd publication-exporter && mvn -q -Dtest=FilesystemManagedSiteInstallerTest test`
Expected: FAILURE.

- [ ] 4.3 **Implement the per-file backup/restore replace protocol for `installManagedGeneration(...)`**,
  applying the same rename-aside/move-new-in/delete-backup-on-success/restore-on-failure shape
  `FilesystemCandidateWorkspace.replaceCandidate(...)` already uses, independently for `ruDestination` and
  `enDestination`:

```java
private void installManagedGeneration(
        Path staging, PublicationIdentity identity, Path ruDestination, Path enDestination) throws IOException {
    Path installationLock = null;
    try (FileLock lock = acquireInstallationLock(identity)) {
        Path ruBackup = replaceLocaleFile(stagedFile(staging, "ru.md"), ruDestination);
        Path enBackup = replaceLocaleFile(stagedFile(staging, "en.md"), enDestination);
        try {
            ensurePayloadRoots();
            writeProvenance(staging);
        } catch (IOException | RuntimeException failure) {
            restoreLocaleBackup(enDestination, enBackup, failure);
            restoreLocaleBackup(ruDestination, ruBackup, failure);
            throw failure;
        }
        deleteLocaleBackupIfPresent(ruBackup);
        deleteLocaleBackupIfPresent(enBackup);
    }
}

private Path replaceLocaleFile(Path source, Path destination) throws IOException {
    Path resolvedDestination = createAndResolveParentDirectories(destination);
    Path resolvedSource = resolveWithinSiteRoot(source);
    Path backup = null;
    if (Files.exists(resolvedDestination, LinkOption.NOFOLLOW_LINKS)) {
        backup = resolvedDestination.resolveSibling(
                resolvedDestination.getFileName() + ".backup-" + UUID.randomUUID()).normalize();
        Files.move(resolvedDestination, resolveWithinSiteRoot(backup), StandardCopyOption.ATOMIC_MOVE);
    }
    Files.move(resolvedSource, resolvedDestination, StandardCopyOption.ATOMIC_MOVE);
    return backup;
}

private void restoreLocaleBackup(Path destination, Path backup, Throwable installationFailure) {
    try {
        Files.deleteIfExists(resolveWithinSiteRoot(destination));
        if (backup != null) {
            Files.move(resolveWithinSiteRoot(backup), resolveWithinSiteRoot(destination), StandardCopyOption.ATOMIC_MOVE);
        }
    } catch (IOException | RuntimeException restoreFailure) {
        installationFailure.addSuppressed(restoreFailure);
    }
}

private void deleteLocaleBackupIfPresent(Path backup) {
    if (backup != null) {
        try {
            Files.deleteIfExists(resolveWithinSiteRoot(backup));
        } catch (IOException error) {
            System.err.println("WARNING: could not remove stale locale backup " + backup + ": " + error);
        }
    }
}
```

Remove the old `rejectIfAlreadyInstalled(...)` call from `installManagedGeneration(...)` and from
`install(...)`'s top-level flow — replacement is now the normal path. (Adjust to your actual current method
shapes/imports — the goal is the same backup/restore behavior as `FilesystemCandidateWorkspace`'s proven
shape, applied per locale file instead of per directory.)

- [ ] 4.4 **Replace the install lock with `FileChannel.tryLock()`**, mirroring
  `FilesystemApprovedSnapshotWorkspace`'s FINAL design exactly (read that file's current
  `withApprovalLock`/lock-acquire/lock-release shape first, then adapt names to this class):

```java
private FileLock acquireInstallationLock(PublicationIdentity identity) throws IOException {
    Path lockFile = createAndResolveParentDirectories(installationLock());
    FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock lock = channel.tryLock();
    if (lock == null) {
        channel.close();
        throw new SiteAlreadyInstalledException(identity);
    }
    return lock;
}
```

(`FileLock`/`FileChannel` — verify you handle `OverlappingFileLockException` for same-JVM contention the same
way `FilesystemApprovedSnapshotWorkspace` does, translating it to the same `SiteAlreadyInstalledException`
collision response. Ensure the channel is closed when the lock is released, whether via try-with-resources or
an explicit close in a `finally`, so the OS releases the advisory lock — do not leak the channel.)

- [ ] 4.5 **Implement `recoverIfNeeded(identity)` per `design.md` D2 (always roll back to old), called at the
  top of `install(...)` before staging/replacing**:

```java
private void recoverIfNeeded(PublicationIdentity identity, Path ruDestination, Path enDestination) {
    boolean recovered = recoverLocaleFile(ruDestination) | recoverLocaleFile(enDestination);
    if (recovered) {
        try {
            writeProvenanceForCurrentState();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}

private boolean recoverLocaleFile(Path destination) {
    Optional<Path> backup = findLocaleBackup(destination);
    if (backup.isEmpty()) {
        return false;
    }
    try {
        Files.deleteIfExists(resolveWithinSiteRoot(destination));
        Files.move(resolveWithinSiteRoot(backup.get()), resolveWithinSiteRoot(destination),
                StandardCopyOption.ATOMIC_MOVE);
        return true;
    } catch (IOException error) {
        throw new UncheckedIOException(error);
    }
}

private Optional<Path> findLocaleBackup(Path destination) {
    Path parent = destination.getParent();
    if (parent == null || !Files.isDirectory(parent)) {
        return Optional.empty();
    }
    String prefix = destination.getFileName() + ".backup-";
    try (var entries = Files.list(parent)) {
        return entries.filter(Files::isRegularFile)
                .filter(entry -> entry.getFileName().toString().startsWith(prefix))
                .findFirst();
    } catch (IOException error) {
        throw new UncheckedIOException(error);
    }
}
```

(`writeProvenanceForCurrentState()` is a small refactor extracting the existing manifest-compute-and-write
logic so `recoverIfNeeded` can call it without needing a `staging` directory — check whether `writeProvenance(staging)`
can be decomposed into "compute+write direct to the canonical path" for this reuse, or whether a small
staging directory is simpler to create just for this call; your call, keep it minimal.)

- [ ] 4.6 **Run the tests from 4.1, plus the full class, plus Task 2's CLI acceptance test**

Run: `cd publication-exporter && mvn -q -Dtest=FilesystemManagedSiteInstallerTest,InstallToSiteCliAcceptanceTest,CheckContentGateContractTest test`
Expected: PASS — this is the point where Task 2's previously-red CLI acceptance test turns GREEN.

- [ ] 4.7 **Write and pass a second acceptance-level test: two release generations through the shared
  contract.** Per this slice's stated acceptance boundary, add (or confirm an existing test already proves)
  the same replace-then-verify sequence exercised first against `NullManagedSiteInstaller` (Task 1) and then
  against `FilesystemManagedSiteInstaller` (this task) — if `FilesystemManagedSiteInstallerTest` and
  `NullManagedSiteInstallerTest` don't already share a common contract-test base, a small parallel test in
  each proving the same behavior (install once, install again with different content, assert the second
  generation's content is what's readable/installed) is sufficient; do not introduce a shared test-base
  abstraction for two occurrences (YAGNI, matches this codebase's own extraction-threshold precedent).

- [ ] 4.8 **Run the full suite**

Run: `cd publication-exporter && mvn -q test`
Expected: BUILD SUCCESS, all tests green.

- [ ] 4.9 **Commit**

```bash
cd publication-exporter
git add src/main/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstaller.java \
        src/test/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstallerTest.java
git commit -m "feat(site): atomic per-file replace with durable recovery and a real cross-process lock (REL-04, REL-05)

Mirrors FilesystemCandidateWorkspace's backup/restore protocol at
per-locale-file granularity, always rolls back to the old generation on
recovery (provenance is the completion marker, written last), and
replaces the fragile createFile-based install lock with the
FileChannel.tryLock() design S09 proved correct with a live SIGKILL
probe."
```

---

## 5. Whole-branch regression pass and requirement traceability check

**Files:** none created/modified — verification only.

- [ ] 5.1 **Run the complete Maven test suite**

Run: `cd publication-exporter && mvn -B test`
Expected: `Tests run: 4XX+, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

- [ ] 5.2 **Manually trace each requirement scenario to its covering test(s)**: REL-03 "Same approved state is
  built twice" (now for a replaced generation) and "Provenance or output is tampered with" → confirm existing
  `CheckContentGateContractTest`/`SiteReleaseManifestTest` coverage still exercises these against a second
  generation, add a case if missing; REL-04 "Inputs remain stable" (unaffected) and "Input changes
  concurrently" → Task 3's input-drift-blocks test; REL-05 "Staged site content is valid" (replace case),
  "Staged content or filesystem is unsafe" (now reachable — confirm a test exists proving live trees stay at
  the prior generation when staged content fails validation against an *existing* prior generation, add one
  if missing), "Installation is interrupted" → Task 4's recovery tests. Do not silently mark this task done if
  any scenario lacks a direct test.

- [ ] 5.3 **Confirm `git status` is clean except for this slice's new/modified files.**

- [ ] 5.4 **This task has no commit of its own** — it is the checkpoint before subagent-driven-development
  hands off to the four parallel review passes and the final whole-branch review.
