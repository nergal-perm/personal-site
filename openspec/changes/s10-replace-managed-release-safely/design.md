## Context

`FilesystemManagedSiteInstaller.install(...)` is create-only: `rejectIfAlreadyInstalled(...)` throws
`SiteAlreadyInstalledException` whenever `src/content/<collection>/{ru,en}/<id>.md` already exist. Unlike
`FilesystemCandidateWorkspace`/`FilesystemApprovedSnapshotWorkspace` (S08/S09), a publication's managed
content is **not** one self-contained directory — it is two separate files living in different locale
subtrees (`src/content/<collection>/ru/<id>.md`, `src/content/<collection>/en/<id>.md`), plus one
site-wide `.astro-export/release-provenance.json` shared across every publication. `installManagedGeneration(...)`
already does ru→en→provenance in sequence with rollback-by-deletion on failure (the create case has nothing
to restore *to* — rollback just removes what was written). The install lock
(`acquireInstallationLock`/`releaseInstallationLock`) still uses the fragile `Files.createFile(...)`-based
approach S09 spent four review rounds replacing with `FileChannel.tryLock()` for the identical class of
problem (stale lock after process death permanently blocking recovery).

## Goals / Non-Goals

**Goals**
- Replacing an existing managed generation for one publication is atomic at the "one coherent generation"
  granularity RVA/REL language uses: a concurrent reader (the site-content gate, Astro's build, or another
  `install-to-site` call) never observes ru from one generation paired with en or provenance from another.
- An interruption during replacement recovers deterministically — this design chooses **always roll back to
  the old generation** on any interruption (see D2), a valid reading of REL-05's "old or new" and the
  simpler, already-established behavior of this exact adapter's create-case rollback.
- A declared input (the approved snapshot's hashes) that changes between planning and commit blocks the
  release, leaving live trees untouched (REL-04).
- Concurrent replacement attempts for the same identity are serialized (real cross-process lock).
- Tamper detection and build-twice determinism (REL-03) continue to hold for a replaced generation with no
  new mechanism — `SiteReleaseManifest` is already recomputed fresh from current tree bytes on every install.

**Non-Goals** (excluded per `proposal.md`)
- Semantic target activation — S20's job.
- Additional content kinds beyond `blog/essay` — S17's job.
- Legacy migration — S21+'s job.

## Decisions

### D1 — Replace via per-file backup/restore, not per-directory

Unlike S08/S09's single backup-directory-per-identity, a managed generation spans three independent paths
(ru.md, en.md, provenance.json — the last shared across all publications). Apply the same
backup-rename/move-new-in/delete-backup-on-success protocol S08/S09 proved, independently to `ru.md` and
`en.md` (backup names: `<id>.md.backup-<uuid>`, sibling to the canonical file in the same locale directory —
confined the same way every other path in this class already is). Provenance is site-wide, not per-
publication, so it is NOT independently backed up per publication; instead, provenance is written **last**,
after both locale files have successfully swapped, using the existing `ATOMIC_MOVE`+`REPLACE_EXISTING`
(already correct for a single shared file — the "old" provenance value is recoverable by recomputing
`SiteReleaseManifest` fresh from whatever the ru/en trees currently contain, so no separate provenance backup
is needed).

Alternative considered: treat the whole `src/content` payload root as one big backed-up directory, mirroring
S08/S09 exactly. Rejected — `src/content` is shared by every publication; backing up the entire tree to
replace one publication's two files would make concurrent installs for *different* publications interfere
with each other's backups, which is strictly worse than today's already-fine-grained per-file writes.

### D2 — Recovery keys off per-locale-file presence, not a rollback-vs-complete policy decision

**Correction (found by Task 4's implementer during self-review, before any code was written): the original
version of this decision — "any leftover backup proves provenance was never reached, so always roll back" —
was unsound.** The write order is: (1) backup old RU, (2) move new RU in, (3) backup old EN, (4) move new EN
in, (5) compute and write provenance from the now-new canonical trees, (6) delete both backups. A crash *or
even a caught, logged `IOException`* between step 5 and step 6 leaves a fully complete, already-successfully-
reported NEW generation sitting next to a stale backup — directly contradicting "any leftover backup means
provenance was never reached." Unconditional rollback in that state would silently destroy a successful
install the next time `install`/recovery runs.

The corrected policy needs no old-vs-new judgement call at all, because `ATOMIC_MOVE` already gives each
locale file exactly three possible states, never a fourth: **fully old** (canonical present, no backup),
**fully new** (canonical present, no backup — the swap and cleanup both completed), or **mid-swap**
(canonical absent, backup present — the crash landed between removing old and installing new). Recovery,
per locale file, independently:

- **Canonical file present, backup also present:** the swap already completed (old or new — irrelevant,
  whichever bytes are at the canonical path are already a complete, valid file by construction of
  `ATOMIC_MOVE`). Delete the stale backup. Do not touch the canonical file.
- **Canonical file absent, backup present:** the swap was interrupted before the new file ever displaced the
  old one. Restore the backup to canonical.
- **Neither present:** nothing to recover for this file.

Recompute and rewrite provenance after any recovery action touched at least one locale file, so it matches
whatever ru/en state recovery leaves behind — this is unconditionally safe now, unlike the rejected policy,
because provenance is a pure function of current tree bytes and is never itself the thing being decided
between.

This replaces the original "always rollback" framing entirely; it is simpler than what it replaces (a
presence check per file, no cross-file coordination needed) and is sound because it never needs to know
whether the current canonical file is the old or new generation — only whether the atomic move that would
produce *some* complete file ever completed.

Alternative considered: mirror S09's "assess both sides, keep whichever is valid, restore the other" logic.
Rejected as unnecessary complexity for this adapter — S09 needed that because its directory-swap makes
"backup still exists AND canonical already has new bytes" a reachable, valid post-crash state (the crash
landed between the new-move succeeding and backup cleanup). Here, because provenance is written strictly
after both locale-file moves succeed and only the backup markers (not provenance) prove completion, "backup
exists" only ever means "this specific file's own move may or may not have finished, but the overall
replace as a whole never reached the provenance-write step that marks true completion" — so unconditional
rollback of any file with a leftover backup is sound without needing to distinguish sub-cases.

### D3 — Real cross-process lock via `FileChannel.tryLock()`, replacing the `createFile`-based one

Replace `acquireInstallationLock`/`releaseInstallationLock`'s `Files.createFile(...)`+`FileAlreadyExistsException`
mechanism with `FileChannel.tryLock()` on the same `.astro-export/install-locks/.site.installing` path,
exactly matching S09's final, independently-verified-with-a-real-`SIGKILL`-probe design
(`FilesystemApprovedSnapshotWorkspace`'s `withApprovalLock`). This is not optional hardening — S09's four
review rounds concluded the `createFile`+reclaim family of designs cannot be made correct, and this project
now has a proven correct alternative; carrying the old design into S10 would reintroduce exactly the bug
class S09 spent four rounds closing. `SiteAlreadyInstalledException` remains the collision response for a
live contender, same as today.

### D4 — Input-drift guard: recapture and recompare the approved snapshot's hashes immediately before commit

`InstallToSiteHandler` (the caller) reads the approved snapshot once to plan the release. Immediately before
`ManagedSiteInstaller.install(...)` commits (inside the lock, right before the ru/en/provenance sequence
begins), re-read the approved snapshot from `ApprovedSnapshotWorkspace` and compare its six content hashes
against what was planned. A mismatch means a concurrent `mark-reviewed` replaced the approved snapshot after
this release was planned — block with existing live trees untouched, mirroring S08 finding 1's
`PrepareHandler` freshness-recheck pattern (same shape: plan against a snapshot, recheck immediately before
the irreversible step, reject on drift).

Alternative considered: guard only at `ManagedSiteInstaller`'s boundary (it re-reads the snapshot itself).
Rejected — `ManagedSiteInstaller` does not currently depend on `ApprovedSnapshotWorkspace` directly (S07's
design keeps them as independent siblings reading the same source, per S07's own corrected scope-pins note);
introducing that dependency now would be a bigger architectural change than threading the recheck through the
handler that already holds both.

## Risks / Trade-offs

- [Risk] Always-rollback-on-interruption (D2) means an operator retrying a replace after a crash always
  redoes the full write, never resumes a "mostly done" install.
  → Mitigation: installs are cheap, local filesystem writes of a few KB; redoing one from scratch is not a
  meaningful cost, and it keeps recovery logic simple and provably correct rather than optimized for a
  vanishingly rare interruption case.
- [Risk] Provenance has no per-publication backup, so if recovery rolls back ru/en but provenance was
  somehow already written (should not happen given D2's "provenance last" ordering, but defensively), a
  brief mismatch could exist until the next install recomputes it.
  → Mitigation: `recoverIfNeeded` always recomputes and rewrites provenance after any rollback, so this
  self-heals on the very next `install-to-site` call for *any* publication, and `check-content.mjs`'s gate
  (REL-03) would catch a real mismatch before an Astro build proceeds in the meantime.
- [Risk] The cross-process lock (D3) is site-wide (one lock path for the whole install-lock directory, per
  S07's existing design), not per-publication — two operators replacing *different* publications' releases
  concurrently serialize unnecessarily.
  → Mitigation: this is S07's existing behavior, unchanged by this slice; scoping the lock to per-publication
  is a separate, optional future improvement, not required by any REL-03/04/05 scenario.

## Migration Plan

Pure additive/internal change to `publication-exporter`. No on-disk format change to a managed generation's
own files — only the transient per-file backup naming convention is new, and it never persists across a
successful operation. Rollback is a plain revert; any leftover backup file from a reverted build is picked up
and rolled back the next time `install-to-site` runs for that publication.

## Open Questions

None outstanding — the functional collaborative-design pass confirmed zero requirement-text gaps
(`scope-pins.md`); the per-file backup/restore, always-rollback recovery policy, real cross-process lock, and
input-drift recheck directly reuse or extend S08/S09's already-proven patterns.
