## Why

S08 made it possible to prepare a validated changed-essay candidate against an approved baseline, but
`MarkReviewedHandler` still unconditionally blocks approving it: `alreadyApprovedResponse()` fires whenever
an approved snapshot already exists, regardless of whether the new candidate is a legitimate, fully
revalidated re-review. Without this slice, a changed essay can be prepared and reviewed but never actually
re-published — Milestone B's "safe repeated publication" promise is incomplete.

## What Changes

- `mark-reviewed`, given a candidate that passes RVA-04's full revalidation (current source bytes, candidate
  completeness, English structure/freshness, semantic-reference map, safe workspace paths) against an
  *existing* approved snapshot, replaces the prior approved RU/EN/reference-map triple with the new one as
  one atomic, coherent unit — never exposing a mixed old/new snapshot to a concurrent reader.
- A second approval whose evidence has gone stale (source or candidate changed since the review being
  approved) still blocks, exactly as RVA-04 already requires for the first-approval case — staleness
  blocking is unchanged, only the "otherwise-valid second approval" outcome changes from unconditional block
  to atomic replace.
- An interruption during replacement (crash, write failure) deterministically recovers to exactly the old
  complete snapshot or the new complete snapshot on the next inspection or retry — never a torn/mixed state
  — and reports the recovery outcome rather than silently guessing.
- Concurrent replacement attempts for the same publication identity are serialized so no interleaved or
  partial write is ever observable.
- First-approval behavior (S05, no prior approved snapshot) is unchanged.

**BREAKING (requirement text):** RVA-05's existing "A second approval is attempted" scenario currently
mandates that the request "is blocked rather than silently replacing" whenever an approved snapshot already
exists — this directly contradicts this slice's purpose and is corrected in `specs/review-and-approval/spec.md`
(see Capabilities below). The correction is scoped narrowly: a *stale* second approval still blocks (RVA-04
already requires this); only the *revalidated, non-stale* second-approval case changes from block to replace.

**Out of scope:** replacing the release tree (`build-from-review`, `install-to-site` — S10's job) and
workflow-queue refresh (S11's job).

## Capabilities

### New Capabilities
(none — this slice extends existing capabilities' requirements, it does not introduce a new bounded
capability)

### Modified Capabilities
- `review-and-approval`: RVA-04 is realized as-is (its revalidation gate already covers the stale-second-
  approval-blocks case generically). RVA-05 gets a genuine requirement-text correction: its "A second approval
  is attempted" scenario is rewritten from unconditional-block to atomic-replace-when-revalidated, and its
  "Approval completes"/"Approval is interrupted" scenarios are confirmed to already cover the replace path
  generically (no separate wording needed — "one coherent snapshot" and "the prior complete snapshot or the
  new complete snapshot" already anticipate a pre-existing prior snapshot). RVA-06 is realized as-is (its
  "tampering blocks" scenario already applies regardless of whether the snapshot being tampered with is the
  first or a replacement).

## Impact

- `publication-exporter` module: `mark-reviewed` command/handler (`MarkReviewedHandler`), `ApprovedSnapshotWorkspace`
  port and its Filesystem/Null adapters (atomic replace-with-recovery semantics, mirroring the backup/restore
  pattern S08 already added to `FilesystemCandidateWorkspace` for the same class of problem), a per-publication
  exclusion lock (reusing S08's `PrepareHandler` locking approach rather than inventing a second mechanism).
- Not touched: `prepare`, `inspect-publication`, `build-from-review`, `install-to-site`,
  `refresh-publication-queue`.
