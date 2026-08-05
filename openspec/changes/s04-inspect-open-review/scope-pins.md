# S04 scope pins

These notes record requirement scope that S04 realizes but does not modify. They are stored outside
`specs/` so OpenSpec archives only the one real delta (`review-and-approval` RVA-01), while this change
retains its scope evidence.

## Review and approval

`openspec/specs/review-and-approval/spec.md` already fully specifies RVA-01 through RVA-06 as the
target end state, derived directly from `openspec/requirements-baseline.md` ahead of any implementation.

### Requirement: RVA-02 Produce an exact review plan

Fully in scope for S04, and the existing "First publication is reviewed" scenario already says exactly
what this slice does — no gap, first realization only:

- **In scope** — Scenario: First publication is reviewed (a candidate snapshot and no approved baseline;
  the review plan identifies both candidate languages and states that the baseline is absent). This is
  the exact `baselineState: "absent"` case the plugin's `validateReviewPlan` already expects.
- **Not yet applicable** — Scenario: Existing publication changed (a candidate and a complete approved
  baseline; the plan identifies the complete approved-versus-candidate Russian diff). No approved
  baseline can exist until S05, so `baselineState: "complete"` is unreachable in this slice's acceptance
  boundary and fails closed as unsupported state, not as silently-passing partial behaviour.

## Workflow bridge

`openspec/specs/workflow-bridge/spec.md` already fully specifies BRG-01 through BRG-07.

### Requirement: BRG-04 Report independent publication state dimensions

Fully in scope for S04, and the existing "Candidate is ready but approved snapshot is absent" scenario
already says exactly what this slice does:

- **In scope** — Scenario: Candidate is ready but approved snapshot is absent (a complete valid
  candidate and no approved baseline; candidate state is ready and approved-snapshot state is absent;
  neither is collapsed into `ready_to_publish`). This is the mechanism `InspectPublicationHandler` gains
  in this slice.
- **Not yet applicable** — Scenario: Release is blocked by semantic state. No release exists until S06,
  and semantic-reference inconsistency detection is S13/S19's realization; unreachable in this slice.
- **Already realized, unchanged** — Scenario: No publication work has started (S02's realization; this
  slice adds a new reachable state alongside it without altering it).

### Requirement: BRG-07 Bound editor-launch integration

Fully in scope for S04 on the exporter side (supplying a review plan with safe RU/EN paths), and both
existing scenarios already say exactly what this slice's boundary is:

- **In scope, exporter side only** — Scenario: Review artefacts are launchable. S04 is responsible for
  the GIVEN clause (a review plan with safe RU and EN paths); the WHEN/THEN (both language attempts
  through the editor command, tolerating one launch failure) is plugin-owned behaviour already built and
  tested in `obsidian-plugin` (`launchReviewPlan`, `runZedTarget`) — unmodified by this change.
- **In scope, exporter side only** — Scenario: Active note changes after prepare. The "immutable prepared
  note path from the preparation response" is plugin-side state (`main.js`'s post-prepare flow), already
  implemented; this slice does not change `prepare`'s response shape (still identity + status only, per
  S03's own BRG-01/BRG-02 scope pin) and does not add note-path tracking to the exporter.

## Not touched by this change

RVA-03, RVA-04, RVA-05, RVA-06 (approval, revalidation, atomic install, immutability) remain fully
specified in the baseline and are unimplemented until S05/S09. BRG-01, BRG-02, BRG-03, BRG-05, BRG-06
are unaffected — `inspect-publication`'s existing command surface, schema-v2 envelope, and six-state
workflow vocabulary are unchanged aside from the new `reviewPlan` field and the `candidateState` value
now being derived rather than hard-coded, both additive to what BRG-02/BRG-03's existing scenarios
already require of every response.
