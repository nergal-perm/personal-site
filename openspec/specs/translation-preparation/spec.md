# Translation preparation Specification

## Purpose

Prepare a reviewable bilingual candidate for one admitted source note while preserving approved and released state. Evidence: E-PREP, E-REVIEW, and E-REF in `openspec/requirements-baseline.md`.

## Requirements

### Requirement: TRP-01 Prepare one bounded publication candidate

The exporter SHALL prepare at most one publication identity per note-scoped request and SHALL write only the candidate workspace, job workspace, and exporter-owned workflow scalars associated with that request.

#### Scenario: Preparation succeeds
- **GIVEN** one admitted source note and writable safe workspaces
- **WHEN** the operator requests preparation
- **THEN** one candidate snapshot is installed for that publication identity
- **AND** no approved snapshot or site tree changes

#### Scenario: Another publication is invalid
- **GIVEN** the requested source note is valid and an unrelated publication is invalid
- **WHEN** the requested note is prepared
- **THEN** no job or candidate is created for the unrelated publication

### Requirement: TRP-02 Diff against the approved Russian baseline

When an approved Russian snapshot exists, preparation SHALL derive translation scope from semantic source changes against that exact approved snapshot; without an approved snapshot, the entire normalized source is new translation scope.

#### Scenario: Approved baseline is absent
- **GIVEN** an admitted source note with no approved snapshot
- **WHEN** preparation computes translation scope
- **THEN** the review plan identifies a first-publication candidate and no fictional baseline

#### Scenario: Only serialization noise changed
- **GIVEN** an approved Russian snapshot and a current source whose normalized public meaning is unchanged
- **WHEN** preparation computes the diff
- **THEN** no semantic translation scope is reported

#### Scenario: Public meaning changed
- **GIVEN** an approved Russian snapshot and a current source with added, removed, or changed public meaning
- **WHEN** preparation computes the diff
- **THEN** the complete changed scope is represented in the translation job and review plan

### Requirement: TRP-03 Preserve a known-good English candidate until replacement is valid

The exporter SHALL install a newly generated English candidate only after it passes structural, freshness, identity, and safety validation; failure SHALL preserve the prior candidate English bytes when they exist.

#### Scenario: Generated English is valid
- **GIVEN** a translation worker returns a complete candidate matching the requested source and invariants
- **WHEN** candidate validation succeeds
- **THEN** RU, EN, and semantic-reference candidate files are installed as one coherent candidate snapshot

#### Scenario: Translation fails or is stale
- **GIVEN** a prior valid English candidate and a worker result that fails, is malformed, is stale, or does not match the requested job
- **WHEN** preparation finishes
- **THEN** the prior English candidate bytes remain unchanged
- **AND** the result reports `translation_failed` or `stale` with diagnostics

### Requirement: TRP-04 Isolate and authenticate translation jobs

Each preparation request SHALL use a unique bounded job workspace, SHALL accept results only from its own job and source fingerprint, and SHALL prevent concurrent jobs from overwriting a newer valid result.

#### Scenario: Matching job completes
- **GIVEN** a job result within the configured job root whose job ID and source fingerprint match the request
- **WHEN** the result is collected
- **THEN** it is eligible for validation and candidate installation

#### Scenario: Job result crosses a boundary
- **GIVEN** a result reached by traversal, symlink, hard-link substitution, wrong job ID, wrong source fingerprint, or a concurrent stale writer
- **WHEN** the result is collected
- **THEN** it is rejected before candidate installation
- **AND** existing candidate and approved snapshots remain unchanged

### Requirement: TRP-05 Preserve semantic occurrence identity through preparation

In semantic-reference mode, preparation SHALL create Russian and English candidates whose reference occurrences have the same stable IDs and order, reusing a prior occurrence ID only when the occurrence identity still matches.

#### Scenario: Existing occurrence remains the same
- **GIVEN** a previously mapped semantic occurrence and an unchanged corresponding source occurrence
- **WHEN** a new candidate is prepared
- **THEN** the occurrence retains its prior reference ID in RU, EN, and `references.json`

#### Scenario: Translation reorders or invents occurrences
- **GIVEN** a generated English candidate whose semantic occurrence IDs or order differ from Russian
- **WHEN** candidate validation runs
- **THEN** the candidate is blocked before installation

### Requirement: TRP-06 Update only exporter-owned workflow scalars

When preparation reports workflow state in the source note, the exporter SHALL update only its declared workflow scalar fields using a guarded atomic replacement and SHALL preserve all other source bytes and file permissions.

#### Scenario: Workflow state update is safe
- **GIVEN** source bytes still match the bytes validated for preparation and workflow keys are unique scalars
- **WHEN** the workflow state is updated
- **THEN** only the declared workflow scalar values change
- **AND** permissions and all other bytes remain unchanged

#### Scenario: Source changed concurrently
- **GIVEN** the source note changes after validation or contains duplicate, aliased, or malformed workflow keys
- **WHEN** a workflow update is attempted
- **THEN** the update is blocked without rewriting the source
