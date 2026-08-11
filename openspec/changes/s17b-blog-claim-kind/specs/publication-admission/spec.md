## MODIFIED Requirements

### Requirement: ADM-03 Require a unique publication identity and supported kind

An admitted source note SHALL have `publicCollection`, a lowercase-slug `publicId`, and a supported `publicContentType`; the combination SHALL map to exactly one supported publication kind and one publication identity.

#### Scenario: Supported kind is accepted
- **GIVEN** a selected note with valid identity fields and all fields required by its collection/content-type pair
- **WHEN** its publication contract is evaluated
- **THEN** exactly one of essay, claim, note, book, album, concept, or curated editorial page is selected

#### Scenario: A blog/note fixture is admitted as a distinct kind from essay
- **GIVEN** a selected note with `publicCollection: blog`, `publicContentType: note`, a valid `publicId`, `id`, `title`, and `description`
- **WHEN** its publication contract is evaluated
- **THEN** it is admitted as the `blog/note` kind, not `blog/essay`
- **AND** an essay fixture admitted in the same run is unaffected and still resolves to `blog/essay`

#### Scenario: A blog/claim fixture is admitted as a distinct kind from essay and note
- **GIVEN** a selected note with `publicCollection: blog`, `publicContentType: claim`, a valid `publicId`, `id`, `title`, `description`, and a non-blank `statement`
- **WHEN** its publication contract is evaluated
- **THEN** it is admitted as the `blog/claim` kind, not `blog/essay` or `blog/note`
- **AND** an essay or note fixture admitted in the same run is unaffected and still resolves to its own kind

#### Scenario: Ambiguous or incomplete identity is blocked
- **GIVEN** a selected note with missing identity fields, an invalid public ID, an unsupported collection/content-type pair, or a duplicate publication identity
- **WHEN** its publication contract is evaluated
- **THEN** the note is blocked with field-specific diagnostics

### Requirement: ADM-04 Enforce kind-specific source contracts

The exporter SHALL validate the required metadata and structured body sections for the selected publication kind before content preparation or release.

#### Scenario: Kind-specific contract is complete
- **GIVEN** a selected book with author metadata, an album with artist/work metadata and required body sections, a concept with description and definition, an editorial page with an allowed page key and valid structured body, a note with `id`, `title`, and `description` and no required structured body, a claim with `id`, `title`, `description`, and a non-blank `statement`, or an essay with title and description
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes

#### Scenario: Kind-specific contract is incomplete
- **GIVEN** a selected note missing a field or body section required by its publication kind
- **WHEN** the note is validated
- **THEN** processing is blocked before translation or release
- **AND** the diagnostic names the kind and missing requirement

#### Scenario: blog/note has no required structured body
- **GIVEN** a selected `blog/note` fixture with valid identity fields, `title`, and `description`, and no `observation`, `model`, `boundary`, or `experiment` content
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes, since `blog/note` requires no structured body section

#### Scenario: blog/claim requires a non-blank statement
- **GIVEN** a selected `blog/claim` fixture with valid identity fields, `title`, `description`, and a non-blank `statement`
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes, whether or not any relationship array (`supports`/`opposes`/`assumes`/`refines`/`contradicts`) or `sources` entry is populated — those remain optional, per `blogClaim`'s site schema

#### Scenario: blog/claim missing its statement is blocked
- **GIVEN** a selected `blog/claim` fixture with valid identity fields but a missing or blank `statement`
- **WHEN** the note is validated
- **THEN** the kind-specific contract fails
- **AND** the diagnostic names `blog/claim` and the missing `statement` requirement

### Requirement: ADM-06 Export the publication contract for authoring tools

The exporter SHALL expose a deterministic, machine-readable publication contract describing supported kinds, required fields, allowed values, and structured-body requirements. The contract is a standalone JSON document (`contractVersion` plus one entry per supported kind) returned by the `write-publication-contract` command; it is not wrapped in the `BridgeResponse` schema-v2 envelope used by note-scoped commands, since a contract has no operation outcome (no `ok`/`status`/`diagnostics`/`identity`) — it is a declarative description of what a valid publication looks like. For each kind, the contract states: its `collection`/`contentType` pair; each required frontmatter field with its expected type, and where applicable an explicit allowed-value list (e.g. `publicCollection` must be `"blog"`) or a documented pattern (e.g. `publicId` must match the lowercase route-slug pattern); and its structured-body requirements, which is an empty list for every kind implemented so far (`blog/essay`, `blog/note`, and `blog/claim`; `blog/claim`'s relationship arrays and `sources` are optional metadata, not a required structured body section, so they do not appear as a structured-body requirement).

#### Scenario: Contract is requested twice
- **GIVEN** the same exporter edition and no contract changes
- **WHEN** an authoring tool requests the publication contract twice
- **THEN** both responses are byte-equivalent after the declared serialization normalization
- **AND** the normalization is: stable per-kind field order, kinds sorted by `(collection, contentType)`, no timestamp or environment-dependent value in the document

#### Scenario: Contract describes every installed kind
- **GIVEN** the exporter edition implements `blog/essay`, `blog/note`, and `blog/claim`
- **WHEN** the publication contract is requested
- **THEN** the contract lists exactly one entry per installed kind, sorted by `(collection, contentType)`
- **AND** each entry's required fields match that kind's own `PublicationKind` implementation's enforced fields, with their actual allowed values or pattern
- **AND** each entry's structured-body requirements are empty, since no kind implemented so far requires one

#### Scenario: Contract describes blog/claim's required statement field
- **GIVEN** the exporter edition implements `blog/claim`
- **WHEN** the publication contract is requested
- **THEN** the `blog/claim` entry's required fields include `statement` as a non-blank string requirement, alongside the shared identity fields (`publish`, `publicCollection`, `publicContentType`, `publicId`, `id`, `title`, `description`)
- **AND** its structured-body requirements remain empty, since the relationship arrays (`supports`/`opposes`/`assumes`/`refines`/`contradicts`) and `sources` are optional, not required

#### Scenario: Validator and published contract disagree
- **GIVEN** a fixture accepted by the published contract but rejected by runtime validation, or the reverse
- **WHEN** the contract conformance harness runs
- **THEN** the exporter edition fails acceptance
- **AND** the harness draws every fixture from one shared fixture table also exercised by that kind's own `PublicationKind` validation tests, so the two suites cannot silently diverge in what fixtures they cover
