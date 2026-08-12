## MODIFIED Requirements

### Requirement: ADM-03 Require a unique publication identity and supported kind

An admitted source note SHALL have `publicCollection`, a lowercase-slug `publicId`, and a supported `publicContentType`; the combination SHALL map to exactly one supported publication kind and one publication identity.

#### Scenario: Supported kind is accepted
- **GIVEN** a selected note with valid identity fields and all fields required by its collection/content-type pair
- **WHEN** its publication contract is evaluated
- **THEN** exactly one of essay, claim, note, book, album, concept, or curated editorial page is selected

#### Scenario: A music/album fixture is admitted as a distinct kind from blog, bibliography, and concepts kinds
- **GIVEN** a selected note with `publicCollection: music`, `publicContentType: album`, a valid `publicId`, `id`, `title`, `description`, `artist`, `work`, `context`, and `association`
- **WHEN** its publication contract is evaluated
- **THEN** it is admitted as the `music/album` kind, not any `blog/*`, `bibliography/book`, or `concepts/concept` kind
- **AND** an essay, note, claim, book, or concept fixture admitted in the same run is unaffected and still resolves to its own kind

#### Scenario: Ambiguous or incomplete identity is blocked
- **GIVEN** a selected note with missing identity fields, an invalid public ID, an unsupported collection/content-type pair, or a duplicate publication identity
- **WHEN** its publication contract is evaluated
- **THEN** the note is blocked with field-specific diagnostics

### Requirement: ADM-04 Enforce kind-specific source contracts

The exporter SHALL validate the required metadata and structured body sections for the selected publication kind before content preparation or release.

#### Scenario: Kind-specific contract is complete
- **GIVEN** a selected book with a non-empty `authors` list and no `selectedQuote`, an album with `artist`, `work`, `context`, `association`, and any combination of optional `format`, `listenFor`, `care`, `releaseDate`, `genreTags`, `streamingUrl`, and `bandcampEmbedUrl`, a concept with `id`, `title`, and `description` and any combination of optional `notThis`, `relations`, and `examples`, an editorial page with an allowed page key and valid structured body, a note with `id`, `title`, and `description` and no required structured body, a claim with `id`, `title`, `description`, and a non-blank `statement`, or an essay with title and description
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes

#### Scenario: Kind-specific contract is incomplete
- **GIVEN** a selected note missing a field or body section required by its publication kind
- **WHEN** the note is validated
- **THEN** processing is blocked before translation or release
- **AND** the diagnostic names the kind and missing requirement

#### Scenario: music/album requires artist, work, context, and association
- **GIVEN** a selected `music/album` fixture with valid identity fields, `title`, and `description`, but a missing or blank `artist`, `work`, `context`, or `association`
- **WHEN** the note is validated
- **THEN** the kind-specific contract fails
- **AND** the diagnostic names `music/album` and the missing requirement

#### Scenario: music/album optional fields must match their declared shape
- **GIVEN** a selected `music/album` fixture whose `listenFor` or `genreTags` value is not a list of non-blank strings, or whose `format`, `care`, `releaseDate`, `streamingUrl`, or `bandcampEmbedUrl` is present but blank
- **WHEN** the note is validated
- **THEN** processing is blocked before translation or release
- **AND** the diagnostic names the offending field

#### Scenario: music/album absent optional fields are accepted
- **GIVEN** a selected `music/album` fixture with valid identity fields, `artist`, `work`, `context`, and `association`, and none of `format`, `listenFor`, `care`, `releaseDate`, `genreTags`, `streamingUrl`, or `bandcampEmbedUrl` populated
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes, since every field beyond the required set is optional per `site/src/content.config.ts`'s declared `music` schema

### Requirement: ADM-06 Export the publication contract for authoring tools

The exporter SHALL expose a deterministic, machine-readable publication contract describing supported kinds, required fields, allowed values, and structured-body requirements. The contract is a standalone JSON document (`contractVersion` plus one entry per supported kind) returned by the `write-publication-contract` command; it is not wrapped in the `BridgeResponse` schema-v2 envelope used by note-scoped commands, since a contract has no operation outcome (no `ok`/`status`/`diagnostics`/`identity`) — it is a declarative description of what a valid publication looks like. For each kind, the contract states: its `collection`/`contentType` pair; each required frontmatter field with its expected type, and where applicable an explicit allowed-value list (e.g. `publicCollection` must be `"blog"`) or a documented pattern (e.g. `publicId` must match the lowercase route-slug pattern); its optional fields and their shape; and its structured-body requirements. For the kinds implemented after this slice, those requirements remain empty (`blog/essay`, `blog/note`, `blog/claim`, `bibliography/book`, `concepts/concept`, and `music/album`), because every implemented kind's supported fields live in frontmatter.

#### Scenario: Contract is requested twice
- **GIVEN** the same exporter edition and no contract changes
- **WHEN** an authoring tool requests the publication contract twice
- **THEN** both responses are byte-equivalent after the declared serialization normalization
- **AND** the normalization is: stable per-kind field order, kinds sorted by `(collection, contentType)`, no timestamp or environment-dependent value in the document

#### Scenario: Contract describes every installed kind
- **GIVEN** the exporter edition implements `blog/essay`, `blog/note`, `blog/claim`, `bibliography/book`, `concepts/concept`, and `music/album`
- **WHEN** the publication contract is requested
- **THEN** the contract lists exactly one entry per installed kind, sorted by `(collection, contentType)`
- **AND** each entry's required fields match that kind's own `PublicationKind` implementation's enforced fields, with their actual allowed values, type, or pattern
- **AND** each entry's structured-body requirements are empty, since no implemented kind in this slice requires a structured body section

#### Scenario: Contract describes music/album's required and optional fields
- **GIVEN** the exporter edition implements `music/album`
- **WHEN** the publication contract is requested
- **THEN** the `music/album` entry's required fields include `artist`, `work`, `context`, and `association` as non-blank string requirements, alongside the shared identity fields (`publish`, `publicCollection`, `publicContentType`, `publicId`, `id`, `title`, `description`)
- **AND** the entry's optional fields document `format` and `care` as optional non-blank strings, `releaseDate`, `streamingUrl`, and `bandcampEmbedUrl` as optional non-blank strings, `listenFor` as an optional non-blank string list, and `genreTags` as an optional non-blank string list
- **AND** its structured-body requirements remain empty

#### Scenario: Validator and published contract disagree
- **GIVEN** a fixture accepted by the published contract but rejected by runtime validation, or the reverse
- **WHEN** the contract conformance harness runs
- **THEN** the exporter edition fails acceptance
- **AND** the harness draws every fixture from one shared fixture table also exercised by that kind's own `PublicationKind` validation tests, so the two suites cannot silently diverge in what fixtures they cover
