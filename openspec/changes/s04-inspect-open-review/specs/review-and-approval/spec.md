## MODIFIED Requirements

### Requirement: RVA-01 Inspect publication state without mutation

The exporter SHALL provide a read-only inspection result that distinguishes candidate state, approved-snapshot state, semantic-reference state, release state, freshness, and diagnostics.

#### Scenario: Inspection observes a complete candidate
- **GIVEN** a safe complete candidate and an approved baseline
- **WHEN** the operator inspects the publication
- **THEN** the result describes each state independently and supplies a review plan for the exact candidate
- **AND** no source, candidate, approved, job, or site bytes change

#### Scenario: First-publication candidate is reviewed
- **GIVEN** a safe complete candidate and no approved baseline
- **WHEN** the operator inspects the publication
- **THEN** candidate state is reported as ready and approved-snapshot state is reported as absent
- **AND** the result supplies a review plan for the exact candidate whose baseline is absent
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

RVA-01's only pre-existing "complete candidate" scenario bundles candidate-completeness together with an approved baseline's existence — the S08/S09 "changed publication" case. No existing RVA-01 scenario covered the combination S04 actually produces: a complete candidate with no approved baseline at all. RVA-02 already had the exact first-publication scenario text ("First publication is reviewed") and BRG-04 already had the exact state-independence text ("Candidate is ready but approved snapshot is absent"), but RVA-01 itself — the umbrella read-only-inspection requirement — had a hole at exactly this combination. The new "First-publication candidate is reviewed" scenario closes it as a permanent, first-class addition to the baseline, following the same reasoning S02 gave RVA-01's own all-absent scenario and S03 gave SEM-03's empty-map scenario.

## Not touched by this change

RVA-02 ("First publication is reviewed"), workflow-bridge's BRG-04 ("Candidate is ready but approved snapshot is absent") and BRG-07 (both scenarios) already carry scenario text that exactly describes the rest of this slice's mechanism with no gap — see `scope-pins.md`. RVA-03 through RVA-06 (approval, revalidation, atomic install, immutability) remain fully specified in the baseline and are unimplemented until S05/S09. `semanticReferenceState` continues to be reported as `absent` for a first-publication candidate in this slice — SEM-03's empty-map realization (S03) is not surfaced through inspection until the requirement that introduces that reporting dimension is itself in scope; no baseline text changes as a result.
