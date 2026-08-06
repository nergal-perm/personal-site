# S05 scope pins

These notes record requirement scope that S05 realizes but does not modify. They are stored outside
`specs/` so OpenSpec archives only the one real delta (`review-and-approval` RVA-05's new scenario),
while this change retains its scope evidence.

## Review and approval

`openspec/specs/review-and-approval/spec.md` already fully specifies RVA-01 through RVA-06 as the
target end state, derived directly from `openspec/requirements-baseline.md` ahead of any implementation.

### Requirement: RVA-03 Advance approval only through explicit mark-reviewed work

Fully in scope for S05, and both existing scenarios already say exactly what this slice does — no gap,
first realization only:

- **In scope** — Scenario: Operator approves an exact candidate (a complete candidate that still matches
  the validated source and English result; that exact candidate becomes the approved snapshot; the
  response is successful only after the approved snapshot is durable). This is exactly `mark-reviewed`'s
  first-approval success path.
- **In scope** — Scenario: Non-approval command runs (`prepare`, `inspect`, `refresh`, etc. leave approved
  bytes/hashes unchanged). Trivially true in this slice since no other command reads or writes the new
  approved-snapshot store at all.

### Requirement: RVA-04 Revalidate at the approval boundary

Fully in scope for S05, restricted to the first-approval case — no competing approval or existing
approved snapshot exists yet to contend with, per the implementation plan's own "(exact first candidate)"
qualifier on this requirement for S05.

- **In scope** — Scenario: Candidate remains exact (source and candidate bytes still match preparation
  evidence; approval may proceed). The "no competing approval holds the lock" clause in this scenario's
  GIVEN is vacuously satisfied — nothing in this slice can attempt a second concurrent approval, so no
  real locking mechanism is built yet.
- **In scope** — Scenario: Candidate or source changed (source, candidate, reference map, or paths differ
  from prepared values; approval is blocked; the prior approved snapshot — absent, in this slice's only
  reachable case — remains exact).
- **Not yet applicable** — the requirement's "per-publication exclusion lock" phrase has no reachable
  contention case until a second approval attempt exists to contend with the first; that is S09's
  replacement/recovery work, not S05's.

### Requirement: RVA-05 (crash-recovery scenario only; the new-scenario delta is in `specs/`)

- **In scope, satisfied by construction** — Scenario: Approval is interrupted. A crash during staging
  (before the atomic move) leaves the final destination untouched — retrying `mark-reviewed` simply lands
  in "no approved snapshot yet," the same coherent state as never having approved; a crash after the move
  means installation already succeeded. There is no partial/mixed state an atomic move onto a fresh,
  create-only directory can produce, matching the same convention `CandidateWorkspace#install` already
  established and already reviewed clean in S03/S04. No explicit read-back-and-verify step is added on
  top of the atomic-move guarantee — there is no failure mode in this slice's scope it would protect
  against.

## Semantic references

### Requirement: SEM-03 Validate the reference map as a bound snapshot member

Already realized at the candidate boundary by S03 (`ReferenceMap.empty(...)`, written into
`references.json` by `prepare`). S05 reuses the same reference map unchanged — copying it verbatim into
the approved snapshot, not re-deriving or re-validating its meaning independently — so no new scenario is
needed: RVA-04's own "Candidate or source changed... reference map... differ... blocked" scenario is what
enforces "the reference map still matches" at the approval boundary; SEM-03 continues to define what a
structurally valid map looks like, unchanged.

- **In scope** — Scenario: First-publication candidate has no semantic references (the empty map, already
  produced by `prepare`, is accepted as the approved snapshot's third member exactly as it was accepted as
  the candidate's third member — same bytes, same identity/hash binding).
- **Not yet applicable** — Scenario: Reference map matches candidate / Reference map is inconsistent, for
  any *non-empty* occurrence set — SEM-02's occurrence assignment and PCM-03's link resolution are both
  later (S13/S19); every reference map this slice ever handles is the always-empty one S03 already
  produces.

## Workflow bridge

### Requirement: BRG-01 Support the plugin command set without shell interpretation

Already fully realized for `inspect-publication` and `prepare` by S01-S04; S05 extends the same
realization to `mark-reviewed` — the "Note-scoped command is invoked" scenario is generic across every
note-scoped command and already covers `mark-reviewed` without new scenario text, the same reasoning S03
gave when extending this requirement to `prepare`.

## Not touched by this change

RVA-01, RVA-02 (inspection and review-plan reporting) are unaffected — this slice does not change what
`inspect-publication` reports; `approvedSnapshotState` remains hard-coded `"absent"` there until a later
slice wires the new approved-snapshot store into inspection (tracked as an open question in `design.md`,
not assumed in scope here). RVA-06 (post-approval immutability/tamper detection) remains fully specified
in the baseline and is unimplemented until S09. BRG-02 through BRG-07 are unaffected — the schema-v2
envelope and six-state workflow vocabulary are unchanged aside from `mark-reviewed` now producing a real
response instead of no response at all, additive to what BRG-02/BRG-03's existing scenarios already
require of every command.
