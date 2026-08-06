# S06 scope pins

These notes record requirement scope that S06 realizes but does not modify. They are stored outside
`specs/` so OpenSpec archives only the one real delta (`release-materialization` REL-02's new
zero-occurrence scenario), while this change retains its scope evidence.

## Release materialization

`openspec/specs/release-materialization/spec.md` already fully specifies REL-01 through REL-06 as the
target end state, derived directly from `openspec/requirements-baseline.md` ahead of any implementation.

### Requirement: REL-01 Read approved snapshots only

Fully in scope for S06, and both existing scenarios already say exactly what this slice does — no gap,
first realization only:

- **In scope** — Scenario: Candidate differs from approved snapshot (public content reflects the approved
  snapshot and ignores candidate bytes). This is exactly `build-from-review`'s behavior: it never reads
  `CandidateWorkspace` at all, only `ApprovedSnapshotWorkspace`.
- **In scope** — Scenario: Selected publication lacks a safe approved snapshot (release is blocked before
  live site trees change). The only reachable sub-case in this slice is "absent" — no approved snapshot
  exists yet for the identity. "Partial," "unsafe," and "inconsistent" approved state, and the "live site
  trees" this scenario protects, both presuppose a live managed tree and a replacement/corruption path that
  do not exist until REL-04/REL-05 land in S07/S10. Read narrowly for S06: blocking means writing nothing
  at all into the fresh output root, which is the strict precursor of "live site trees remain unchanged"
  once a live tree exists to protect.

### Requirement: REL-03 Bind output to deterministic provenance

Fully in scope for S06 for its determinism half only; the tamper-detection half is not yet applicable.

- **In scope** — Scenario: Same approved state is built twice (identical managed content and normalized
  provenance). This is exactly S06's own acceptance test: building from the same approved snapshot twice,
  in different filesystem enumeration orders, produces byte-identical output files and provenance.
- **Not yet applicable** — Scenario: Provenance or output is tampered with (the site content gate verifies
  it and blocks). The site content gate is REL-06, introduced in S07. Nothing in S06 reads provenance back
  to verify it; provenance is written once and never re-checked in this slice.

### Requirements REL-04, REL-05, REL-06

Not touched. Fully specified in the baseline; unimplemented until S07 (first managed-site install/build)
and S10 (safe managed-release replacement).

## Public content model

`openspec/specs/public-content-model/spec.md` already fully specifies PCM-01 through PCM-06.

### Requirement: PCM-01 Produce a deterministic normalized manifest

Already realized at the candidate boundary by S03 (`PrepareHandler` produces the normalized RU/EN bodies
once, deterministically, from source bytes). S06 does not re-normalize anything — it copies the approved
snapshot's already-normalized bytes verbatim into the release output. The release boundary's own
determinism claim is carried by REL-03's "Same approved state is built twice" scenario, not by a second,
redundant PCM-01 scenario at the release boundary.

- **In scope, satisfied by construction** — Scenario: Same inputs are built twice. Release output is a
  pure function of the approved snapshot's bytes; running `build-from-review` twice against the same
  approved snapshot, in different filesystem enumeration orders, produces identical release files (this is
  the same fact REL-03 asserts, from the release-provenance angle instead of the manifest angle).
- **Not re-exercised** — Scenario: Workflow metadata changes. Workflow-owned frontmatter fields
  (`refresh-publication-queue`'s domain, S11) never reach the approved snapshot at all — `mark-reviewed`
  only ever installs `ruBody`/`enBody`/`referenceMap`, none of which carry workflow scalars. Nothing in
  S06's scope can exercise this scenario differently than it already was at S03.

### Requirement: PCM-02 Project only fields allowed by the publication kind

Already realized at the candidate boundary by S02/S03 for the `blog/essay` kind. S06 performs no
projection of its own — it writes the approved RU/EN bodies exactly as installed, so whatever fields
`PrepareHandler` already validated and projected at `prepare` time are what ships to release, unchanged.

- **In scope, satisfied by construction** — Scenario: Kind-specific projection succeeds. The approved
  snapshot's bodies are already a validated `blog/essay` projection (S02's `EssayAdmission` plus S03's
  `PrepareHandler`); `build-from-review` adds no new field, drops no required one, and performs no
  re-validation, so nothing about the projection's field set can change between candidate and release.
- **Not yet applicable** — Scenario: Unsupported value reaches projection. This describes a validation
  failure during manifest construction (`prepare`/S03's job); `build-from-review` never constructs a
  manifest entry, only writes already-projected bytes, so this failure mode cannot occur in this slice's
  code path.

## Not touched by this change

`workflow-bridge` (BRG-01 through BRG-07) is unaffected — whether `build-from-review` produces a
schema-v2 `BridgeResponse` at all is an open question for the technical collaborative-design pass, not a
functional-requirements question; if it does, no existing BRG scenario needs new text, following the same
reasoning S03/S05 gave when extending BRG-01/BRG-02/BRG-03's existing note-scoped-command coverage to
`prepare`/`mark-reviewed`. `translation-preparation`, `review-and-approval`, and `semantic-references` are
fully specified in the baseline and unaffected by this slice: S06 reads an already-approved snapshot and
never touches candidates, jobs, or reference-map validation. `legacy-transition` remains entirely
unimplemented and out of scope until S21+.
