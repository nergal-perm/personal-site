## MODIFIED Requirements

### Requirement: ADM-03 Require a unique publication identity and supported kind

An admitted source note SHALL have `publicCollection`, a lowercase-slug `publicId`, and a supported `publicContentType`; the combination SHALL map to exactly one supported publication kind and one publication identity.

#### Scenario: Supported kind is accepted
- **GIVEN** a selected note with valid identity fields and all fields required by its collection/content-type pair
- **WHEN** its publication contract is evaluated
- **THEN** exactly one of essay, claim, note, book, album, concept, or curated editorial page is selected

#### Scenario: An editorial/curated_page (about) fixture is admitted as a distinct kind from blog, bibliography, music, and concepts kinds
- **GIVEN** a selected note with `publicCollection: editorial`, `publicContentType: curated_page`, a valid `publicId` equal to `editorialPage`, `editorialPage: about`, `id`, and `title`
- **WHEN** its publication contract is evaluated
- **THEN** it is admitted as the `editorial/curated_page` kind, not any `blog/*`, `bibliography/book`, `music/album`, or `concepts/concept` kind
- **AND** an essay, note, claim, book, album, or concept fixture admitted in the same run is unaffected and still resolves to its own kind

#### Scenario: An editorial/curated_page fixture with an unsupported page key is blocked, not admitted
- **GIVEN** a selected note with `publicCollection: editorial`, `publicContentType: curated_page`, and `editorialPage` set to one of the eight legacy page keys other than `about` (`home`, `essays`, `claims`, `notes`, `music`, `library`, `concepts`, `now`)
- **WHEN** its publication contract is evaluated
- **THEN** the note is blocked with a diagnostic naming `editorialPage` and stating that only `about` is currently supported
- **AND** it is not admitted as `editorial/curated_page` or any other kind

#### Scenario: Ambiguous or incomplete identity is blocked
- **GIVEN** a selected note with missing identity fields, an invalid public ID, an unsupported collection/content-type pair, or a duplicate publication identity
- **WHEN** its publication contract is evaluated
- **THEN** the note is blocked with field-specific diagnostics

### Requirement: ADM-04 Enforce kind-specific source contracts

The exporter SHALL validate the required metadata and structured body sections for the selected publication kind before content preparation or release.

#### Scenario: Kind-specific contract is complete
- **GIVEN** a selected book with a non-empty `authors` list and no `selectedQuote`, an album with `artist`, `work`, `context`, and `association`, a concept with `id`, `title`, and `description` and any combination of optional `notThis`, `relations`, and `examples`, an `about` curated editorial page with `editorialPage: about`, `id`, `title`, and a structured body containing exactly one `## Кратко` section, one `## Eyebrow` section, one `## Лид` section, a `## Принципы` section with at least one `### `-headed principle, and one `## Колофон` section, a note with `id`, `title`, and `description` and no required structured body, a claim with `id`, `title`, `description`, and a non-blank `statement`, or an essay with title and description
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes

#### Scenario: Kind-specific contract is incomplete
- **GIVEN** a selected note missing a field or body section required by its publication kind
- **WHEN** the note is validated
- **THEN** processing is blocked before translation or release
- **AND** the diagnostic names the kind and missing requirement

#### Scenario: editorial/curated_page (about) requires editorialPage, id, and title, but no description
- **GIVEN** a selected `editorial/curated_page` fixture with `editorialPage: about` but a missing or blank `id` or `title`
- **WHEN** the note is validated
- **THEN** the kind-specific contract fails
- **AND** the diagnostic names `editorial/curated_page` and the missing requirement
- **AND** a fixture with valid `editorialPage`, `id`, and `title` but no `description` field is not blocked for that reason, since `editorial/curated_page` has no `description` requirement — its body's `## Кратко` section supplies the equivalent public summary

#### Scenario: editorial/curated_page (about) body grammar is incomplete
- **GIVEN** a selected `editorial/curated_page` (`about`) fixture whose body is missing its `## Кратко`, `## Eyebrow`, `## Лид`, `## Принципы`, or `## Колофон` section, or whose `## Принципы` section contains zero `### `-headed principles
- **WHEN** the note is validated
- **THEN** processing is blocked before translation or release
- **AND** the diagnostic names the missing or empty section

#### Scenario: editorial/curated_page (about) publicId must equal editorialPage
- **GIVEN** a selected `editorial/curated_page` fixture whose `publicId` does not equal its `editorialPage` value
- **WHEN** the note is validated
- **THEN** the kind-specific contract fails
- **AND** the diagnostic names `publicId`

### Requirement: ADM-06 Export the publication contract for authoring tools

The exporter SHALL expose a deterministic, machine-readable publication contract describing supported kinds, required fields, allowed values, and structured-body requirements. The contract is a standalone JSON document (`contractVersion` plus one entry per supported kind) returned by the `write-publication-contract` command; it is not wrapped in the `BridgeResponse` schema-v2 envelope used by note-scoped commands, since a contract has no operation outcome (no `ok`/`status`/`diagnostics`/`identity`) — it is a declarative description of what a valid publication looks like. For each kind, the contract states: its `collection`/`contentType` pair; each required frontmatter field with its expected type, and where applicable an explicit allowed-value list (e.g. `publicCollection` must be `"blog"`) or a documented pattern (e.g. `publicId` must match the lowercase route-slug pattern); its optional fields and their shape; and its structured-body requirements. `editorial/curated_page` is the first kind whose structured-body requirements are non-empty, naming its required sections (`## Кратко`, `## Eyebrow`, `## Лид`, `## Принципы` with at least one principle, `## Колофон`) rather than leaving that part of the contract empty.

#### Scenario: Contract is requested twice
- **GIVEN** the same exporter edition and no contract changes
- **WHEN** an authoring tool requests the publication contract twice
- **THEN** both responses are byte-equivalent after the declared serialization normalization
- **AND** the normalization is: stable per-kind field order, kinds sorted by `(collection, contentType)`, no timestamp or environment-dependent value in the document

#### Scenario: Contract describes every installed kind
- **GIVEN** the exporter edition implements `blog/essay`, `blog/note`, `blog/claim`, `bibliography/book`, `concepts/concept`, `music/album`, and `editorial/curated_page`
- **WHEN** the publication contract is requested
- **THEN** the contract lists exactly one entry per installed kind, sorted by `(collection, contentType)`
- **AND** each entry's required fields match that kind's own `PublicationKind` implementation's enforced fields, with their actual allowed values, type, or pattern

#### Scenario: Contract describes editorial/curated_page's required fields and structured-body requirement
- **GIVEN** the exporter edition implements `editorial/curated_page`
- **WHEN** the publication contract is requested
- **THEN** the `editorial/curated_page` entry's required fields include `editorialPage` (allowed value `about` for this edition) alongside the shared identity fields (`publish`, `publicCollection`, `publicContentType`, `publicId`, `id`, `title`), and no `description` requirement
- **AND** the entry's structured-body requirements name its five required sections and the minimum-one-principle rule
- **AND** its optional fields document `publicSearchable` as an optional boolean — `topics`/`links` are not part of this kind's contract, matching every other implemented kind, none of which declares them either

#### Scenario: Validator and published contract disagree
- **GIVEN** a fixture accepted by the published contract but rejected by runtime validation, or the reverse
- **WHEN** the contract conformance harness runs
- **THEN** the exporter edition fails acceptance
- **AND** the harness draws every fixture from one shared fixture table also exercised by that kind's own `PublicationKind` validation tests, so the two suites cannot silently diverge in what fixtures they cover
