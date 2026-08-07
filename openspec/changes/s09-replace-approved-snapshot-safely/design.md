## Context

`ApprovedSnapshotWorkspace.install(...)` is create-only today: `FilesystemApprovedSnapshotWorkspace` throws
`ApprovedSnapshotAlreadyExistsException` whenever the destination directory already exists, and
`MarkReviewedHandler.markReviewedAdmittedEssay(...)` short-circuits to `alreadyApprovedResponse()` before
ever calling `install(...)` a second time. `FilesystemCandidateWorkspace` already solved the identical
"replace a directory atomically with crash recovery" problem in S08 (finding 2 of its final review): rename
the existing directory aside to `<name>-backup-<uuid>`, move the new content into place, delete the backup
only after the move succeeds, and restore the backup on failure. `PrepareHandler` (S08 finding 1) already
solved the identical "serialize concurrent same-identity operations" problem: a static
`ConcurrentHashMap<PublicationIdentity, ReentrantLock>` guarding the translate-validate-install sequence.

S09's requirement is strictly harder than S08's finding-2 fix in one respect: RVA-05 says recovery must work
"when the workspace is next inspected **or** approval is retried" — not only within the same call that
failed. A process kill between the backup rename and the new-content move (or between the new-content move
and backup cleanup) must be recoverable by a *later*, possibly different, process — the on-disk state itself
must carry enough evidence to recover deterministically, not just an in-memory try/catch.

## Goals / Non-Goals

**Goals**
- `mark-reviewed` replaces an existing approved snapshot atomically when the candidate passes RVA-04's full
  revalidation, using the same backup/restore mechanism `FilesystemCandidateWorkspace` already proved.
- Recovery from an interruption is durable across process restarts: the next `read`/`find` call, or the next
  `install`/replace attempt, deterministically finishes or rolls back a half-completed replace found on disk.
- Concurrent `mark-reviewed` calls for the same publication identity are serialized.
- A stale second approval (source/candidate changed since review) still blocks — no behavior change there.

**Non-Goals** (excluded per `proposal.md`)
- Release-tree replacement (`ReleaseOutputStore`, `ManagedSiteInstaller`) — S10's job.
- Workflow-queue refresh — S11's job.
- Any new semantic-occurrence or reference-map concept.

## Decisions

### D1 — `ApprovedSnapshotWorkspace.install(...)` becomes replace-or-create, with the same backup/restore
protocol as `FilesystemCandidateWorkspace`

Drop `ApprovedSnapshotAlreadyExistsException`'s create-only enforcement from `install(...)` itself. Instead:
`FilesystemApprovedSnapshotWorkspace.install(...)` stages the new snapshot, then calls a `replaceApproved(...)`
method structurally identical to `FilesystemCandidateWorkspace.replaceCandidate(...)` (rename-existing-aside,
move-new-in, delete-backup-on-success, restore-backup-on-failure). `NullApprovedSnapshotWorkspace` drops its
`ensureNotAlreadyInstalled(...)` guard the same way and simply overwrites its in-memory map entry — the fake
has no crash-recovery concept to fake (there's no process boundary to survive in-memory), so its contract is
just "replace succeeds, second install is not an error."

Alternative considered: keep `install(...)` create-only and add a separate `replace(...)` method. Rejected —
`MarkReviewedHandler` already branches on whether an approved snapshot exists (`findApprovedSnapshot(...)`),
so the caller already knows which case it's in; a single `install(...)` that internally handles both is less
API surface and mirrors `FilesystemCandidateWorkspace.install(...)`'s own shape (which has always silently
replaced, per S08 finding 2 — `CandidateWorkspace` never had a create-only constraint at all). Keeping
`ApprovedSnapshotAlreadyExistsException` around **only** for the genuinely-concurrent race case (two threads
both pass the "does it exist" check) preserves its value without conflating it with "an approved snapshot
already exists, please replace it," which is now normal, expected traffic.

### D2 — Durable recovery via a self-describing on-disk marker, checked on every `read`/`find`/`install`

The existing backup-directory naming (`<approved-directory>-backup-<uuid>`) is not enough on its own to
recover deterministically after a crash, because a stray backup with no way to know which install attempt it
belongs to is ambiguous if two backups could ever coexist. Since replacement is now serialized per-identity
(D3) and a backup is always deleted before the *next* replace attempt begins (recovery runs first), at most
one backup can exist per identity at any time — so "a backup directory exists" is itself the durable marker,
no separate journal file is needed. Recovery logic, run at the top of `read(...)`, `find(...)`, and
`install(...)`:

1. If no backup directory exists for this identity: normal state, nothing to do.
2. If a backup directory exists **and** the canonical approved directory is a complete, valid snapshot: the
   previous replace's new-content move already succeeded before the crash (only backup cleanup was
   interrupted) — delete the stale backup and continue.
3. If a backup directory exists **and** the canonical approved directory is missing or incomplete: the
   previous replace crashed after the backup rename but before (or during) the new-content move — restore
   the backup to the canonical path and continue.
4. If a backup directory exists and is *itself* incomplete/corrupt (should not happen given `ATOMIC_MOVE`
   renames a whole already-complete directory, but checked defensively): report a integrity failure rather
   than silently guessing, per RVA-05's own wording — do not attempt a partial restore.

Alternative considered: a separate `.replace-in-progress` journal file recording old/new paths and phase,
inspected on startup (the `StagedDirectoryInstall`/install-lock journaling style `ManagedSiteInstaller`
already uses for site installs, per S07). Rejected for this slice — that mechanism exists because
`ManagedSiteInstaller` can be replacing *multiple* managed roots in one logical operation with a shared
install-wide lock file; `ApprovedSnapshotWorkspace` replaces exactly one self-contained directory per
identity, and the per-identity backup-existence check is sufficient and strictly simpler. If a future slice
needs richer recovery reporting (e.g. distinguishing "recovered to old" from "recovered to new" in the
`BridgeResponse`), that can be layered on without changing this on-disk protocol.

### D3 — Per-publication exclusion lock, same mechanism as `PrepareHandler`, separate registry

`MarkReviewedHandler` gets its own static `ConcurrentHashMap<PublicationIdentity, ReentrantLock>`, guarding
revalidation-through-install exactly the way `PrepareHandler`'s `INSTALL_LOCKS` guards
translate-through-install. A separate registry instance, not the same one `PrepareHandler` uses: they guard
different resources (`ApprovedSnapshotWorkspace` vs `CandidateWorkspace`) and different operations
(`mark-reviewed` vs `prepare`) — two concurrent `prepare`/`mark-reviewed` calls for the same identity operate
on different workspaces and are not the race this lock exists to prevent. This satisfies the design
constraint to reuse S08's *mechanism* (proven, minimal, in-process `ReentrantLock` per identity) without
conflating two independently-scoped locks into one, which would serialize unrelated operations for no
correctness benefit.

### D4 — Revalidation stays exactly as RVA-04 already specifies it, generalized to the replace path by
construction

`MarkReviewedHandler.stalenessDiagnostics(...)` already compares current source/candidate bytes against the
prepared reference-map hashes; nothing in that method's logic assumes "no approved snapshot exists yet." The
only change is *after* revalidation passes: instead of `alreadyApprovedResponse()` when an approved snapshot
is found, proceed to install (which now replaces, per D1). No new revalidation check is introduced.

## Risks / Trade-offs

- [Risk] A backup directory left over from a version of the code predating this slice (there shouldn't be
  any, since `install(...)` never created one before now) could be misread as "a replace is in progress."
  → Mitigation: the backup-detection recovery logic only activates for directories matching the exact
  `<approved-directory>-backup-<uuid>` naming this slice introduces; nothing on disk today matches it.
- [Risk] Recovery runs on every `read`/`find`/`install` call, adding a directory-existence check to every
  approved-snapshot lookup, including the hot `inspect-publication`/`build-from-review` paths.
  → Mitigation: the check is one `Files.exists(...)` call in the common (no-backup) case — negligible cost,
  matches the existing per-call confinement checks (`requireWithinReviewRoot`) already paid on every lookup.
- [Risk] The in-process `ReentrantLock` registry (D3) does not serialize across separate CLI process
  invocations (the real Obsidian plugin spawns one process per command) — two operators approving the same
  publication from two machines simultaneously could still race at the OS level.
  → Mitigation: this is the identical, already-accepted residual from S08's `dec-20260807-s08-translation-
  worker-trust-boundary-8bab0bc6` decision (single-operator deployment model) — not a new gap this slice
  introduces, and out of scope to close here.

## Migration Plan

Pure additive/internal change to `publication-exporter`. No on-disk format change to an approved snapshot's
own files — only the transient backup-directory convention is new, and it never persists across a successful
operation. Rollback is a plain revert; any leftover backup directory from a reverted build would be picked
up and recovered (restored) the next time a non-reverted build runs `read`/`find`/`install` against it.

## Open Questions

None outstanding — the RVA-05 requirement-text correction was resolved in the functional collaborative-design
pass (`scope-pins.md`); the backup/restore and locking mechanisms directly reuse S08's already-proven
patterns rather than introducing new ones.
