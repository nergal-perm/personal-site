## MODIFIED Requirements

### Requirement: SEM-01 Require stable source-owned semantic identities

Every selected source note and every directly referenced private target SHALL have a stable, unique, human-assigned source ID before semantic preparation or release; the exporter SHALL NOT derive identity from a path, title, public ID, or route.

#### Scenario: All required source IDs are valid
- **GIVEN** the selected source and each direct private target have unique valid source IDs
- **WHEN** semantic references are planned
- **THEN** identities are resolved from those source IDs

#### Scenario: Required source ID is absent or duplicated
- **GIVEN** the selected source or a direct private target lacks a source ID or shares one with another note
- **WHEN** semantic preparation is requested
- **THEN** processing is blocked as `metadata_blocked` before a translation job is requested or a candidate is mutated
- **AND** no path-derived identity is allocated

## Why this is a real delta, not a scope pin

The existing scenario's THEN clause said only "before candidate mutation." That was accurate but ambiguous
for this slice: `prepare` also dispatches a translation-worker job (TRP-01), an external, side-effecting
step that itself precedes candidate mutation. Nothing in the prior wording said the identity check must
gate the *job request* too, rather than merely the eventual candidate write. Since S02 only checked the
source's own identity — a check that is trivially available before anything else happens — the ordering
question was moot until now: S18 is the first slice where the identity check depends on resolving link
targets from the body, which is also where a translation job could plausibly be requested first if the two
were not explicitly ordered. The revised wording makes the fail-closed-before-job guarantee explicit rather
than implied, matching this slice's own acceptance boundary ("missing or duplicate target identity returns
metadata_blocked before job or candidate mutation" in `openspec/implementation-plan.md`). No scenario
`GIVEN`/`WHEN` changed, and no new scenario was added — this is a precision fix to an existing THEN clause,
not new behavior.

## Not touched by this change

The scope of *which* targets and *which* duplicates this slice evaluates is recorded in this change's
`scope-pins.md` (direct private targets only, duplicates scoped to the notes touched by this prepare
operation, public targets and self-links excluded, unresolved/nonexistent targets left to S13's existing
safe-label behavior) — none of that narrows the requirement text itself, which already covered the general
case. SEM-02, SEM-03, SEM-04, and SEM-05 remain fully specified in the baseline and are unimplemented until
S19 and S20 respectively; their requirement text is unaffected here.
