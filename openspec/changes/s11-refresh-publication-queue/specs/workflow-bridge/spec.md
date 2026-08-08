## MODIFIED Requirements

### Requirement: BRG-04 Report independent publication state dimensions

Inspection responses SHALL report candidate, approved-snapshot, semantic-reference, and release state independently, alongside overall workflow status, freshness, review plan, and diagnostics.

#### Scenario: Candidate is ready but approved snapshot is absent
- **GIVEN** a complete valid candidate and no approved baseline
- **WHEN** inspection runs
- **THEN** candidate state is ready and approved-snapshot state is absent
- **AND** neither is collapsed into `ready_to_publish`

#### Scenario: Release is blocked by semantic state
- **GIVEN** candidate and approved snapshots are complete but semantic references are inconsistent
- **WHEN** inspection runs
- **THEN** semantic-reference and release states explain the block independently of candidate freshness

#### Scenario: No publication work has started
- **GIVEN** an admitted note with a valid publication identity and no candidate, approved snapshot, semantic-reference map, or release ever produced
- **WHEN** inspection runs
- **THEN** candidate, approved-snapshot, semantic-reference, and release state are each reported as absent, independently of one another
- **AND** the response is `ok: true` with a workflow status that reflects "admitted, nothing prepared yet" rather than collapsing to `metadata_blocked`

#### Scenario: Approved snapshot exists with no pending candidate
- **GIVEN** an admitted note with an installed approved snapshot and no candidate present
- **WHEN** inspection runs
- **THEN** candidate state is absent and approved-snapshot state is ready
- **AND** the response's overall workflow status is `ready_to_publish`, not `not_prepared`

## Why this is a real delta, not a scope pin

BRG-04 already covers "candidate ready, approved absent" (first scenario), "both present but semantic references
block release" (second), and "neither present" (third). It never covered the fourth combination — approved
present, candidate absent — which is exactly the steady state a publication sits in after a successful
`mark-reviewed` with no subsequent edit. `InspectPublicationHandler.inspect()` today only branches on whether a
*candidate* is present (`candidatePaths.isPresent() && candidateSnapshot.isPresent()`); when it is not, every case
falls through to `notPreparedResponse()` regardless of approved-snapshot state, so a fully published, unchanged
essay currently reports `not_prepared` — a genuine defect relative to what BRG-04's own requirement text promises
("report... approved-snapshot... state independently"), not a case the existing wording anticipated and left
unstated. This slice's shared `WorkflowStateClassifier` (built for `refresh-publication-queue`, per
`s11-refresh-publication-queue/design.md`) is what both fixes it and is reused by `InspectPublicationHandler`, so
the two commands cannot disagree on this case per BRG-05.

No other BRG-04 scenario changes. BRG-01, BRG-05, BRG-06 (`workflow-bridge`) and TRP-06 (`translation-preparation`)
are realized exactly as already written — see `scope-pins.md` for why they need no requirement-text change,
including why BRG-06's translating clause is satisfied by construction rather than by active detection.
