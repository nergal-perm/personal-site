## MODIFIED Requirements

### Requirement: RVA-01 Inspect publication state without mutation

The exporter SHALL provide a read-only inspection result that distinguishes candidate state, approved-snapshot state, semantic-reference state, release state, freshness, and diagnostics.

#### Scenario: Inspection observes a complete candidate
- **GIVEN** a safe complete candidate and an approved baseline
- **WHEN** the operator inspects the publication
- **THEN** the result describes each state independently and supplies a review plan for the exact candidate
- **AND** no source, candidate, approved, job, or site bytes change

#### Scenario: Approved baseline is partial or unsafe
- **GIVEN** only one approved language exists or an approved path is unsafe
- **WHEN** the operator inspects the publication
- **THEN** approved-snapshot state is blocked with a specific diagnostic
- **AND** absence is not misreported as a complete baseline

#### Scenario: No candidate, approval, or release exists yet
- **GIVEN** a validly admitted note with no candidate, approved snapshot, semantic-reference map, or release ever produced
- **WHEN** the operator inspects the publication
- **THEN** candidate state, approved-snapshot state, semantic-reference state, and release state are each reported as absent
- **AND** absence in one dimension does not block or collapse the report of the other independent dimensions
- **AND** the response is successful (`ok: true`), since an admitted note with nothing prepared yet is not a blocked note

## Why this is a real delta, not a scope pin

Unlike S01 (which pinned already-baselined scenarios verbatim with no delta), S02 exercises a genuine
gap in the baseline: neither existing RVA-01 scenario shows the all-states-absent case, because both
were written assuming at least a candidate exists. Every valid essay S02 inspects has none of
candidate, approved snapshot, semantic-reference map, or release yet — no earlier slice creates them —
so this is the first slice able to observe that case at all. The new scenario above captures it as a
first-class, permanent addition to the baseline (archived normally, not `--skip-specs`), per the
operator's explicit decision to treat this as a documented gap rather than an implicit corollary of the
existing requirement text.

## Not touched by this change

RVA-02, RVA-03, RVA-04, RVA-05, and RVA-06 remain fully specified in the baseline and are unimplemented
until S04, S05, and S09 respectively. Their requirement text is unaffected here.
