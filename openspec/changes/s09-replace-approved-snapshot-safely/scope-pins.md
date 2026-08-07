# S09 scope pins

These notes record the functional collaborative-design pass over S09's requirement set. The one genuine delta
(RVA-05's second-approval scenario) is in `specs/review-and-approval/spec.md`. Everything else below is
realized, not modified — recorded here so the scope evidence survives archival even though `specs/` only
carries the real delta.

## Review and approval

`openspec/specs/review-and-approval/spec.md` already fully specifies RVA-01 through RVA-06.

### Requirement: RVA-04 Revalidate at the approval boundary

Fully in scope for S09, no gap. Both existing scenarios ("Candidate remains exact" / "Candidate or source
changed") are worded generically over "approval" — they do not distinguish a first approval from a
replacement approval. Applying them unchanged to the second-approval case is exactly what makes S09's
staleness-blocking behavior correct without a new scenario: a stale second approval blocks for the identical
reason a stale first approval already blocks. The "per-publication exclusion lock" phrase in RVA-04's own
requirement text is realized by this slice for the first time (S05 approved without one, since only one
candidate could ever be install-only) — see `design.md` for the locking mechanism.

### Requirement: RVA-06 Keep approved snapshots immutable outside approval

Fully in scope, no gap. "Candidate is prepared after approval" (approved bytes remain unchanged, continue to
define the release baseline) and "Approved bytes are tampered with" (blocked with integrity evidence) both
already apply regardless of whether the approved snapshot being protected is the first one or a replacement —
neither scenario's wording is scoped to "the first approval only." This slice adds no new tampering-detection
mechanism; it reuses whatever integrity check already exists (or is realized generically) for reading an
approved snapshot.

### Requirements RVA-01, RVA-02, RVA-03

Not touched. RVA-01 (candidate-ready state) and RVA-02 (review plan) are realized by S02/S04/S08 and
unaffected by approval mechanics. RVA-03 (explicit `mark-reviewed` is the only path to approval) is realized
by S05 and unaffected — S09 changes what a *second* `mark-reviewed` call does, not who is authorized to call
it or what other commands must not advance approval.

## Semantic references, release materialization, workflow bridge

Not touched. S09 introduces no new semantic-occurrence concept (`references.json`'s shape is unchanged — the
replaced snapshot's reference map is whatever the revalidated candidate already carries), reads/writes no
release-tree artifact (`ReleaseOutputStore`, `ManagedSiteInstaller` are S06/S07's job, explicitly excluded per
`proposal.md`), and adds no new bridge command or response shape beyond `mark-reviewed`'s existing
`BridgeResponse.approved(...)` outcome, now reachable from a second call for the same identity.

## Conclusion

Only `review-and-approval` RVA-05 is a real delta. RVA-04 and RVA-06 are realized as-is; RVA-01 through RVA-03
and every other capability are untouched by this slice.
