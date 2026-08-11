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

#### Scenario: Ambiguous or incomplete identity is blocked
- **GIVEN** a selected note with missing identity fields, an invalid public ID, an unsupported collection/content-type pair, or a duplicate publication identity
- **WHEN** its publication contract is evaluated
- **THEN** the note is blocked with field-specific diagnostics

### Requirement: ADM-04 Enforce kind-specific source contracts

The exporter SHALL validate the required metadata and structured body sections for the selected publication kind before content preparation or release.

#### Scenario: Kind-specific contract is complete
- **GIVEN** a selected book with author metadata, an album with artist/work metadata and required body sections, a concept with description and definition, an editorial page with an allowed page key and valid structured body, a note with `id`, `title`, and `description` and no required structured body, or an essay with title and description
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
