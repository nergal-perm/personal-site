# Publication admission Specification

## Purpose

Define which vault notes enter publication work and prove that a requested note is safe and complete enough to process. Evidence: E-ADM and E-GOV in `openspec/requirements-baseline.md`.
## Requirements
### Requirement: ADM-01 Discover explicitly selected notes

The exporter SHALL discover Markdown source notes whose parsed frontmatter value `publish` is Boolean `true`, including notes in normally ignored vault paths, and SHALL exclude absent, false, string-valued, or malformed publication flags.

#### Scenario: Selected note is discovered
- **GIVEN** a vault-relative Markdown file with parsed frontmatter `publish: true`
- **WHEN** publication discovery scans the vault
- **THEN** the file is present exactly once in the selected-note set

#### Scenario: Lookalike publication flag is excluded
- **GIVEN** a Markdown file whose `publish` value is absent, false, or the string `"true"`
- **WHEN** publication discovery scans the vault
- **THEN** the file is absent from the selected-note set

### Requirement: ADM-02 Confine note requests to the vault

The exporter SHALL accept only existing, regular, vault-relative `.md` source-note paths whose resolved path remains within the configured vault root.

#### Scenario: Safe relative path is admitted
- **GIVEN** an existing regular Markdown file reached by a vault-relative path without indirection outside the vault
- **WHEN** an operator requests note-scoped processing
- **THEN** preflight admits that exact file

#### Scenario: Escaping path is blocked
- **GIVEN** an absolute path, traversal path, non-Markdown path, missing file, or symlink resolving outside the vault
- **WHEN** an operator requests note-scoped processing
- **THEN** preflight blocks before candidate, approved, workflow, or site state changes
- **AND** a diagnostic identifies the rejected path predicate without exposing unrelated private paths

### Requirement: ADM-03 Require a unique publication identity and supported kind

An admitted source note SHALL have `publicCollection`, a lowercase-slug `publicId`, and a supported `publicContentType`; the combination SHALL map to exactly one supported publication kind and one publication identity.

#### Scenario: Supported kind is accepted
- **GIVEN** a selected note with valid identity fields and all fields required by its collection/content-type pair
- **WHEN** its publication contract is evaluated
- **THEN** exactly one of essay, claim, note, book, album, concept, or curated editorial page is selected

#### Scenario: Ambiguous or incomplete identity is blocked
- **GIVEN** a selected note with missing identity fields, an invalid public ID, an unsupported collection/content-type pair, or a duplicate publication identity
- **WHEN** its publication contract is evaluated
- **THEN** the note is blocked with field-specific diagnostics

### Requirement: ADM-04 Enforce kind-specific source contracts

The exporter SHALL validate the required metadata and structured body sections for the selected publication kind before content preparation or release.

#### Scenario: Kind-specific contract is complete
- **GIVEN** a selected book with author metadata, an album with artist/work metadata and required body sections, a concept with description and definition, an editorial page with an allowed page key and valid structured body, or an essay with title and description
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes

#### Scenario: Kind-specific contract is incomplete
- **GIVEN** a selected note missing a field or body section required by its publication kind
- **WHEN** the note is validated
- **THEN** processing is blocked before translation or release
- **AND** the diagnostic names the kind and missing requirement

### Requirement: ADM-05 Validate the bounded request, not unrelated notes

Note-scoped commands SHALL validate the requested selected note and its direct safety dependencies without making unrelated invalid vault notes a blocker.

#### Scenario: Unrelated invalid note exists
- **GIVEN** the requested note passes admission and another selected vault note is invalid
- **WHEN** the operator prepares or inspects the requested note
- **THEN** the requested note's result is determined without being blocked by the unrelated note

#### Scenario: Whole-vault release is requested
- **GIVEN** one or more selected notes fail admission
- **WHEN** a whole-vault manifest or release is requested
- **THEN** the aggregate operation is blocked or omits no invalid selected note silently
- **AND** diagnostics identify every selected note that prevents a complete release

### Requirement: ADM-06 Export the publication contract for authoring tools

The exporter SHALL expose a deterministic, machine-readable publication contract describing supported kinds, required fields, allowed values, and structured-body requirements.

#### Scenario: Contract is requested twice
- **GIVEN** the same exporter edition and no contract changes
- **WHEN** an authoring tool requests the publication contract twice
- **THEN** both responses are byte-equivalent after the declared serialization normalization

#### Scenario: Validator and published contract disagree
- **GIVEN** a fixture accepted by the published contract but rejected by runtime validation, or the reverse
- **WHEN** the contract conformance harness runs
- **THEN** the exporter edition fails acceptance

