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

## Why BRG-04 is a real delta, not a scope pin

Same gap as `review-and-approval`'s RVA-01 (see that change's spec delta for the full rationale): the
baseline's two BRG-04 scenarios both assume a candidate exists. S02 is the first slice able to observe
the all-absent case, since no earlier slice produces a candidate, approved snapshot, reference map, or
release. This scenario is added as a permanent baseline addition per the operator's explicit decision.

## Scope pin for requirements not otherwise modified (no delta)

### Requirement: BRG-01 Support the plugin command set without shell interpretation

Already fully realized for `inspect-publication` by S01; unaffected by this change. S02 only changes
what `inspect-publication` reports once path/vault safety passes, not the command surface itself.

### Requirement: BRG-02 Emit bridge schema v2 for the initial replacement release

S01 pinned the blocked/failure path only and explicitly deferred "Successful bridge command returns"
until a valid-note success response existed. S02 introduces that response, so this scenario moves from
deferred to in scope.

- **In scope** — Scenario: Successful bridge command returns (the valid-essay response is exactly one
  schema-v2 JSON value with `ok: true`, `command: "inspect-publication"`, and the command-appropriate
  identity/state fields this change adds).
- **In scope** (reconfirmed) — Scenario: Domain operation is blocked (S01's existing blocked path, now
  also exercised by S02's identity/source-ID blocking causes).
- **In scope** (reconfirmed) — Scenario: Schema major differs (defensive framing only, unchanged).

### Requirement: BRG-03 Keep the bridge contract single-sourced and conformance-tested

Fully in scope, extended rather than newly realized: the Java-side and JS-side conformance tests both
gain coverage of the new valid-essay response shape against the same `bridge-contract/schema-v2.json`.

- **In scope** — Scenario: Either side changes the contract (both conformance tests extended together).
- **In scope** — Scenario: Optional field is added compatibly (the new identity/state fields are
  additive; schema v2's `additionalProperties: true` means no schema file edit is required).

## Not touched by this change

BRG-05 and BRG-06 (the six-state workflow vocabulary and queue refresh) remain fully specified in the
baseline and are unimplemented until S11; the workflow-status string this change emits for the
all-absent case is chosen to be compatible with, but is not yet validated against, that vocabulary.
BRG-07 (bounded editor-launch integration) remains unimplemented until S04. Their requirement text is
unaffected here.
