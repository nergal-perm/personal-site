# Workflow bridge Specification

## Purpose

Provide the Obsidian plugin and operator with a stable, machine-readable, fail-closed command surface and truthful workflow observations. Evidence: E-BRIDGE, E-PREP, E-REVIEW, and the verified plugin/exporter schema mismatch.
## Requirements
### Requirement: BRG-01 Support the plugin command set without shell interpretation

The exporter bridge SHALL support `prepare`, `inspect-publication`, `mark-reviewed`, and `refresh-publication-queue`; note-scoped commands SHALL accept one validated vault-relative Markdown path, while refresh SHALL accept no current-note path.

#### Scenario: Note-scoped command is invoked
- **GIVEN** an active Markdown note with spaces or shell metacharacters in its vault-relative path
- **WHEN** the plugin invokes a note-scoped command as argument boundaries without a shell
- **THEN** the exporter processes the exact path as data rather than executable syntax

#### Scenario: Refresh is invoked
- **GIVEN** the operator requests queue refresh from any editor state
- **WHEN** the plugin invokes `refresh-publication-queue`
- **THEN** no current-note argument is required or accepted

### Requirement: BRG-02 Emit bridge schema v2 for the initial replacement release

The initial replacement exporter SHALL emit exactly one JSON response conforming to the plugin's bridge schema major version 2 for every bridge command, including blocked commands that exit non-zero.

#### Scenario: Successful bridge command returns
- **GIVEN** a bridge command completes successfully
- **WHEN** the plugin parses standard output
- **THEN** it finds exactly one JSON value with integer `schemaVersion: 2`, the requested command, `ok: true`, and command-appropriate fields

#### Scenario: Domain operation is blocked
- **GIVEN** a bridge command is safely blocked by validation or workflow state
- **WHEN** the exporter exits non-zero
- **THEN** standard output still contains exactly one schema-v2 JSON response with `ok: false`, status, and structured diagnostics

#### Scenario: Schema major differs
- **GIVEN** the plugin receives a response whose schema major is not 2
- **WHEN** it validates the response
- **THEN** it rejects the response with observed and expected versions and performs no follow-up mutation

### Requirement: BRG-03 Keep the bridge contract single-sourced and conformance-tested

Command names, required fields, field types, enum values, and compatibility rules SHALL be defined once as a versioned bridge contract consumed by exporter and plugin conformance tests.

#### Scenario: Either side changes the contract
- **GIVEN** the exporter or plugin changes a required field, enum, or supported command
- **WHEN** repository conformance tests run
- **THEN** the change fails until the shared contract edition and both consumers agree

#### Scenario: Optional field is added compatibly
- **GIVEN** schema v2 declares unknown optional fields ignorable
- **WHEN** the exporter adds such a field and the plugin receives it
- **THEN** existing v2 behaviour remains accepted

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

### Requirement: BRG-05 Use the six-state workflow vocabulary consistently

Note and queue observations SHALL classify the operational workflow as exactly one of `metadata_blocked`, `translating`, `ready_for_review`, `ready_to_publish`, `translation_failed`, or `stale`, without treating diagnostics as extra states.

#### Scenario: State predicate is met
- **GIVEN** publication artefacts satisfy exactly one state predicate
- **WHEN** inspection or refresh classifies them
- **THEN** both commands return the same state and supporting diagnostics for the same observation window

#### Scenario: Evidence is uncertain
- **GIVEN** concurrent work, unsafe workspace state, or unreadable evidence prevents a reliable classification
- **WHEN** queue refresh runs
- **THEN** the item is counted as uncertain and is not rewritten to a guessed state

### Requirement: BRG-06 Refresh queue state without disturbing active work

Queue refresh SHALL scan publication candidates, reconcile exporter-owned workflow scalars where evidence is decisive, report updated, unchanged, and uncertain counts, and leave an actively translating publication untouched.

#### Scenario: Stale state is decisively observable
- **GIVEN** a selected note's persisted workflow scalar differs from its unambiguous current artefact state
- **WHEN** refresh runs
- **THEN** only the exporter-owned scalar is updated and the item contributes to `updated`

#### Scenario: Translation lock is active
- **GIVEN** a valid active translation job owns the publication lock
- **WHEN** refresh runs
- **THEN** translating state and job ownership are not overwritten

### Requirement: BRG-07 Bound editor-launch integration

When a review plan is returned, the plugin integration SHALL preflight the editor command, attempt to open both RU and EN artefacts in separate windows, preserve the prepared note identity for follow-up inspection, and bound process time and captured stderr.

#### Scenario: Review artefacts are launchable
- **GIVEN** a review plan with safe RU and EN paths and an available editor command
- **WHEN** the operator opens review
- **THEN** both language artefacts are attempted even if one launch fails

#### Scenario: Active note changes after prepare
- **GIVEN** preparation completed for one note and the editor's active note later changes or is renamed
- **WHEN** the post-prepare review action inspects state
- **THEN** it uses the immutable prepared note path from the preparation response
