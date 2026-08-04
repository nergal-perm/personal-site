# Review and approval Specification

## Purpose

Expose exact review evidence and make explicit human approval the sole transition that advances the approved publication baseline. Evidence: E-REVIEW, E-PREP, E-GOV, and `exporter-java/README.md`.
## Requirements
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

### Requirement: RVA-02 Produce an exact review plan

The review plan SHALL identify the candidate RU and EN artefacts, distinguish first-publication review from changed-publication review, and represent the complete normalized difference from the approved Russian snapshot when one exists.

#### Scenario: First publication is reviewed
- **GIVEN** a candidate snapshot and no approved baseline
- **WHEN** a review plan is requested
- **THEN** the plan identifies both candidate languages and states that the baseline is absent

#### Scenario: Existing publication changed
- **GIVEN** a candidate and a complete approved baseline
- **WHEN** a review plan is requested
- **THEN** the plan identifies both candidate languages and the complete approved-versus-candidate Russian diff

### Requirement: RVA-03 Advance approval only through explicit mark-reviewed work

Only a successful explicit `mark-reviewed` request from the operator SHALL install a new approved snapshot. Prepare, inspect, refresh, export, build, preview, and deployment SHALL NOT advance it.

#### Scenario: Operator approves an exact candidate
- **GIVEN** a complete candidate that still matches the validated source and English result
- **WHEN** the operator explicitly invokes `mark-reviewed`
- **THEN** that exact candidate becomes the approved snapshot
- **AND** the response is successful only after the approved snapshot is durable

#### Scenario: Non-approval command runs
- **GIVEN** any candidate and approved state
- **WHEN** prepare, inspect, refresh, export, build, preview, or deployment work runs
- **THEN** approved snapshot bytes and hashes remain unchanged

### Requirement: RVA-04 Revalidate at the approval boundary

Before approval, the exporter SHALL revalidate the current source bytes, candidate completeness, English structure and freshness, semantic-reference map, and safe workspace paths under a per-publication exclusion lock.

#### Scenario: Candidate remains exact
- **GIVEN** source and candidate bytes still match preparation evidence and no competing approval holds the publication lock
- **WHEN** `mark-reviewed` revalidates them
- **THEN** approval may proceed

#### Scenario: Candidate or source changed
- **GIVEN** source bytes, candidate bytes, reference map, or paths differ from the values prepared and presented for review
- **WHEN** `mark-reviewed` revalidates them
- **THEN** approval is blocked
- **AND** the prior approved snapshot remains exact

### Requirement: RVA-05 Install approved snapshots atomically and recoverably

The approved RU, EN, and semantic-reference map SHALL become visible as one coherent snapshot; a crash or write failure SHALL expose either the prior complete snapshot or the new complete snapshot, never a mixed one.

#### Scenario: Approval completes
- **GIVEN** a valid candidate and safe review workspace
- **WHEN** approved installation succeeds
- **THEN** all approved files correspond to the candidate hashes and are durable before success is reported

#### Scenario: Approval is interrupted
- **GIVEN** failure occurs during approved installation
- **WHEN** the workspace is next inspected or approval is retried
- **THEN** recovery deterministically restores or completes one coherent snapshot
- **AND** the outcome is reported rather than silently guessed

### Requirement: RVA-06 Keep approved snapshots immutable outside approval

After approval, the exporter SHALL treat approved files as immutable inputs in every non-approval workflow and SHALL detect tampering before release.

#### Scenario: Candidate is prepared after approval
- **GIVEN** an approved snapshot and a changed source
- **WHEN** preparation creates a new candidate
- **THEN** approved bytes remain unchanged and continue to define the release baseline

#### Scenario: Approved bytes are tampered with
- **GIVEN** an approved file no longer matches its recorded coherent snapshot
- **WHEN** inspection, approval, or release reads it
- **THEN** the operation is blocked with integrity evidence
