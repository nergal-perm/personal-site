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

### D2 — Recovery uses provenance-vs-tree agreement as the joint completion marker, then restores per-file from backup

**Correction 1 (found by Task 4's implementer, before any code was written): the original "any leftover
backup proves provenance was never reached, so always roll back" premise was unsound.** The write order is:
(1) backup old RU, (2) move new RU in, (3) backup old EN, (4) move new EN in, (5) compute and write provenance
from the now-new canonical trees, (6) delete both backups. A crash *or even a caught, logged `IOException`*
between step 5 and step 6 leaves a fully complete, already-successfully-reported NEW generation sitting next
to a stale backup — directly contradicting "any leftover backup means provenance was never reached."

**Correction 2 (found by Task 4's independent review, after Correction 1 landed): a per-locale-file-
independent presence check — "canonical present ⇒ keep whichever bytes are there, canonical absent ⇒ restore
from backup" — is ALSO unsound, because RU and EN swap independently (steps 1-2 for RU complete fully before
steps 3-4 for EN even begin).** A crash between step 2 and step 3 leaves RU already fully swapped to new
(canonical present, backup present, cleanup pending) while EN was never touched (canonical present with OLD
bytes, no backup, since step 3 never ran). Per-file-independent recovery would keep new RU and leave old EN —
a mixed generation, exactly what REL-05 forbids exposing.

**The correct policy needs a *joint* completion marker spanning both locale files, and provenance already is
one:** provenance is a hash of the current payload tree, written only after step 4 (both files fully new) and
before step 6 (cleanup). So:

1. Recompute a manifest fresh from the CURRENT canonical tree state (the same `SiteReleaseManifest.computeOver(...)`
   call `writeProvenance(...)` already uses) and compare it against whatever provenance is currently recorded
   on disk (if any).
2. **Match (or no leftover backups exist at all):** the last write fully completed and is self-consistent —
   delete any leftover backups (pure post-commit cleanup debris) and leave canonical files untouched.
3. **Mismatch, or provenance missing/unreadable, with at least one backup present:** the swap was interrupted
   before both files and provenance all agreed — restore every locale file that HAS a backup back to its old
   bytes (files with no backup were never touched by the interrupted attempt and are therefore already
   consistent old bytes on their own). After restoring, recompute and rewrite provenance fresh from the now-
   fully-old tree state, so provenance and tree agree again.
4. **Mismatch with zero backups present:** should not be reachable if the lock (D3) genuinely spans the whole
   sequence — fail loudly with a clear diagnostic rather than silently guessing, matching REL-05's "reported
   rather than silently guessed."

This still needs no old-vs-new judgement about any *individual* file — only whether the whole tree, taken
together, currently agrees with its own provenance record. That agreement check is exactly what REL-03's
tamper-detection already computes (`SiteReleaseManifest` freshly recomputed and compared), so recovery reuses
the same mechanism for a second purpose rather than inventing a new one.

Alternative considered: two per-locale-file-independent policies were tried and rejected before landing on
the provenance-vs-tree joint marker above — see Corrections 1 and 2 in this section's own text for why each
failed (unconditional rollback destroys a completed-but-not-yet-cleaned-up install; per-file-independent
presence checks can keep one locale file's new content while restoring the other's old content, producing a
mixed generation). The provenance-vs-tree agreement check is the first version of this policy that is sound,
because it is the only one that judges RU and EN jointly rather than file-by-file.

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

- [Risk] Interruption-triggered rollback (D2) means an operator retrying a replace after a crash mid-swap
  always redoes the full write for whichever locale files were touched, never resumes a "mostly done" install.
  → Mitigation: installs are cheap, local filesystem writes of a few KB; redoing one from scratch is not a
  meaningful cost, and it keeps recovery logic provably correct (a joint marker, not per-file guesswork)
  rather than optimized for a vanishingly rare interruption case.
- [Risk] Recovery's provenance-vs-tree agreement check (D2) means recovery must be able to read and recompute
  provenance correctly even in a partially-written state — a bug in that comparison itself could misjudge
  completeness.
  → Mitigation: the same `SiteReleaseManifest.computeOver(...)` comparison is already REL-03's tamper-
  detection mechanism, independently tested; recovery reuses it rather than inventing a second, unproven
  comparison.
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
