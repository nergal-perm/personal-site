## MODIFIED Requirements

### Requirement: ADM-03 Require a unique publication identity and supported kind

An admitted source note SHALL have `publicCollection`, a lowercase-slug `publicId`, and a supported `publicContentType`; the combination SHALL map to exactly one supported publication kind and one publication identity.

#### Scenario: Supported kind is accepted
- **GIVEN** a selected note with valid identity fields and all fields required by its collection/content-type pair
- **WHEN** its publication contract is evaluated
- **THEN** exactly one of essay, claim, note, book, album, concept, or curated editorial page is selected

#### Scenario: A concepts/concept fixture is admitted as a distinct kind from blog and bibliography kinds
- **GIVEN** a selected note with `publicCollection: concepts`, `publicContentType: concept`, a valid `publicId`, `id`, `title`, and `description`
- **WHEN** its publication contract is evaluated
- **THEN** it is admitted as the `concepts/concept` kind, not any `blog/*` or `bibliography/book` kind
- **AND** an essay, note, claim, or book fixture admitted in the same run is unaffected and still resolves to its own kind

#### Scenario: Ambiguous or incomplete identity is blocked
- **GIVEN** a selected note with missing identity fields, an invalid public ID, an unsupported collection/content-type pair, or a duplicate publication identity
- **WHEN** its publication contract is evaluated
- **THEN** the note is blocked with field-specific diagnostics

### Requirement: ADM-04 Enforce kind-specific source contracts

The exporter SHALL validate the required metadata and structured body sections for the selected publication kind before content preparation or release.

#### Scenario: Kind-specific contract is complete
- **GIVEN** a selected book with a non-empty `authors` list and no `selectedQuote`, an album with artist/work metadata and required body sections, a concept with `id`, `title`, and `description` and any combination of optional `notThis`, `relations`, and `examples`, an editorial page with an allowed page key and valid structured body, a note with `id`, `title`, and `description` and no required structured body, a claim with `id`, `title`, `description`, and a non-blank `statement`, or an essay with title and description
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes

#### Scenario: Kind-specific contract is incomplete
- **GIVEN** a selected note missing a field or body section required by its publication kind
- **WHEN** the note is validated
- **THEN** processing is blocked before translation or release
- **AND** the diagnostic names the kind and missing requirement

#### Scenario: concepts/concept requires only identity, title, and description
- **GIVEN** a selected `concepts/concept` fixture with valid identity fields, `title`, and `description`, and no `notThis`, `relations`, or `examples` content
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes, since `notThis`, `relations`, and `examples` are optional per `site/src/content.config.ts`'s declared `concepts` schema

#### Scenario: concepts/concept optional relations and examples must match the declared shape
- **GIVEN** a selected `concepts/concept` fixture whose `relations` value is not a list, whose `relations` entries are missing a non-blank `name` or `relation`, contain an undeclared field, or whose `examples` value is not a list of non-blank strings
- **WHEN** the note is validated
- **THEN** processing is blocked before translation or release
- **AND** the diagnostic names the offending field (`relations` or `examples`)

#### Scenario: concepts/concept missing identity fields is blocked
- **GIVEN** a selected `concepts/concept` fixture with a missing or blank `id`, `title`, or `description`
- **WHEN** the note is validated
- **THEN** the kind-specific contract fails
- **AND** the diagnostic names `concepts/concept` and the missing requirement

### Requirement: ADM-06 Export the publication contract for authoring tools

The exporter SHALL expose a deterministic, machine-readable publication contract describing supported kinds, required fields, allowed values, and structured-body requirements. The contract is a standalone JSON document (`contractVersion` plus one entry per supported kind) returned by the `write-publication-contract` command; it is not wrapped in the `BridgeResponse` schema-v2 envelope used by note-scoped commands, since a contract has no operation outcome (no `ok`/`status`/`diagnostics`/`identity`) — it is a declarative description of what a valid publication looks like. For each kind, the contract states: its `collection`/`contentType` pair; each required frontmatter field with its expected type, and where applicable an explicit allowed-value list (e.g. `publicCollection` must be `"blog"`) or a documented pattern (e.g. `publicId` must match the lowercase route-slug pattern); its optional fields and their shape; and its structured-body requirements. For the kinds implemented after this slice, those requirements remain empty (`blog/essay`, `blog/note`, `blog/claim`, `bibliography/book`, and `concepts/concept`), because every implemented kind's supported fields live in frontmatter.

#### Scenario: Contract is requested twice
- **GIVEN** the same exporter edition and no contract changes
- **WHEN** an authoring tool requests the publication contract twice
- **THEN** both responses are byte-equivalent after the declared serialization normalization
- **AND** the normalization is: stable per-kind field order, kinds sorted by `(collection, contentType)`, no timestamp or environment-dependent value in the document

#### Scenario: Contract describes every installed kind
- **GIVEN** the exporter edition implements `blog/essay`, `blog/note`, `blog/claim`, `bibliography/book`, and `concepts/concept`
- **WHEN** the publication contract is requested
- **THEN** the contract lists exactly one entry per installed kind, sorted by `(collection, contentType)`
- **AND** each entry's required fields match that kind's own `PublicationKind` implementation's enforced fields, with their actual allowed values, type, or pattern
- **AND** each entry's structured-body requirements are empty, since no implemented kind in this slice requires a structured body section

#### Scenario: Contract describes concepts/concept's optional translated fields
- **GIVEN** the exporter edition implements `concepts/concept`
- **WHEN** the publication contract is requested
- **THEN** the `concepts/concept` entry's required fields are exactly the shared identity fields (`publish`, `publicCollection`, `publicContentType`, `publicId`, `id`, `title`, `description`), matching `site/src/content.config.ts`'s declared `concepts` schema where `notThis`, `relations`, and `examples` all default to absent/empty
- **AND** the entry's optional fields document `notThis` as an optional non-blank string, `relations` as an optional list of `{name, relation}` entries, and `examples` as an optional list of non-blank strings
- **AND** its structured-body requirements remain empty

#### Scenario: Validator and published contract disagree
- **GIVEN** a fixture accepted by the published contract but rejected by runtime validation, or the reverse
- **WHEN** the contract conformance harness runs
- **THEN** the exporter edition fails acceptance
- **AND** the harness draws every fixture from one shared fixture table also exercised by that kind's own `PublicationKind` validation tests, so the two suites cannot silently diverge in what fixtures they cover
