# Publication admission Specification

## Purpose

Define which vault notes enter publication work and prove that a requested note is safe and complete enough to process. Evidence: E-ADM and E-GOV in `openspec/requirements-baseline.md`.
## Requirements
### Requirement: ADM-01 Discover explicitly selected notes

The exporter SHALL discover Markdown source notes whose parsed frontmatter value `publish` is Boolean `true`, including notes in normally ignored vault paths, and SHALL exclude absent, false, string-valued, or malformed publication flags. Discovery order is deterministic (sorted by vault-relative path), not incidental to filesystem or map traversal order — both the in-memory and real vault adapters honor this.

#### Scenario: Selected note is discovered
- **GIVEN** a vault-relative Markdown file with parsed frontmatter `publish: true`
- **WHEN** publication discovery scans the vault
- **THEN** the file is present exactly once in the selected-note set
- **AND** a selected note under a normally tool-ignored path (e.g. a dotfolder) is discovered the same as any other

#### Scenario: Lookalike publication flag is excluded
- **GIVEN** a Markdown file whose `publish` value is absent, false, or the string `"true"`
- **WHEN** publication discovery scans the vault
- **THEN** the file is absent from the selected-note set

#### Scenario: Discovery order is deterministic
- **GIVEN** multiple selected notes at different vault-relative paths
- **WHEN** publication discovery scans the vault twice with no vault changes between scans
- **THEN** both scans return the selected notes in the same sorted-by-path order

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

#### Scenario: A bibliography/book fixture is admitted as a distinct kind from blog kinds
- **GIVEN** a selected note with `publicCollection: bibliography`, `publicContentType: book`, a valid `publicId`, `id`, `title`, `description`, and at least one non-blank author
- **WHEN** its publication contract is evaluated
- **THEN** it is admitted as the `bibliography/book` kind, not any `blog/*` kind
- **AND** a blog essay, note, or claim fixture admitted in the same run is unaffected and still resolves to its own kind

#### Scenario: A concepts/concept fixture is admitted as a distinct kind from blog and bibliography kinds
- **GIVEN** a selected note with `publicCollection: concepts`, `publicContentType: concept`, a valid `publicId`, `id`, `title`, and `description`
- **WHEN** its publication contract is evaluated
- **THEN** it is admitted as the `concepts/concept` kind, not any `blog/*` or `bibliography/book` kind
- **AND** an essay, note, claim, or book fixture admitted in the same run is unaffected and still resolves to its own kind

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

#### Scenario: blog/note has no required structured body
- **GIVEN** a selected `blog/note` fixture with valid identity fields, `title`, and `description`, and no `observation`, `model`, `boundary`, or `experiment` content
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes, since `blog/note` requires no structured body section

#### Scenario: blog/claim requires a non-blank statement
- **GIVEN** a selected `blog/claim` fixture with valid identity fields, `title`, `description`, and a non-blank `statement`
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes, whether or not any relationship array (`supports`/`opposes`/`assumes`/`refines`/`contradicts`) or `sources` entry is populated — those remain optional, per `blogClaim`'s site schema
- **AND** any populated relationship or source entry matches `site/src/content.config.ts`'s declared list/object/scalar shape and contains no undeclared field

#### Scenario: blog/claim source metadata is malformed
- **GIVEN** a selected `blog/claim` fixture whose `sources` value is not a list, whose `link` is not a reference object, whose rich-text token has the wrong shape, or whose source object contains an undeclared field
- **WHEN** the note is validated
- **THEN** processing is blocked before translation or site installation
- **AND** the diagnostic names `sources`
- **AND** no target is resolved and no nested source text is translated as part of this transport-shape validation

#### Scenario: blog/claim missing its statement is blocked
- **GIVEN** a selected `blog/claim` fixture with valid identity fields but a missing or blank `statement`
- **WHEN** the note is validated
- **THEN** the kind-specific contract fails
- **AND** the diagnostic names `blog/claim` and the missing `statement` requirement

#### Scenario: bibliography/book requires a non-empty authors list
- **GIVEN** a selected `bibliography/book` fixture with valid identity fields, `title`, and `description`
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes only when `authors` is a non-empty list of non-blank strings
- **AND** a missing `authors` field, an empty list, a blank author entry, or a non-string author entry blocks processing before translation or release

#### Scenario: bibliography/book selectedQuote is blocked in this slice
- **GIVEN** a selected `bibliography/book` fixture with valid identity fields and authors, but with a populated `selectedQuote` object
- **WHEN** the note is validated
- **THEN** processing is blocked before translation or site installation
- **AND** the diagnostic names `selectedQuote`
- **AND** the reason states that mixed translated structured quote metadata is not supported by this slice

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
- **AND** the `write-publication-manifest` command is this requirement's read-only whole-vault manifest: it reports one entry per selected note (its identity when admitted, or its diagnostics when not), never dropping a failing entry to produce a manifest that looks complete
- **AND** the command reports the manifest as complete only when every selected note admits successfully; otherwise it reports the manifest as incomplete while still listing every selected note's outcome, admitted or not

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

#### Scenario: Contract describes blog/claim's required statement field
- **GIVEN** the exporter edition implements `blog/claim`
- **WHEN** the publication contract is requested
- **THEN** the `blog/claim` entry's required fields include `statement` as a non-blank string requirement, alongside the shared identity fields (`publish`, `publicCollection`, `publicContentType`, `publicId`, `id`, `title`, `description`)
- **AND** its structured-body requirements remain empty, since the relationship arrays (`supports`/`opposes`/`assumes`/`refines`/`contradicts`) and `sources` are optional, not required

#### Scenario: Contract describes bibliography/book's required authors field
- **GIVEN** the exporter edition implements `bibliography/book`
- **WHEN** the publication contract is requested
- **THEN** the `bibliography/book` entry's required fields include `authors` as a non-empty list-of-strings requirement, alongside the shared identity fields (`publish`, `publicCollection`, `publicContentType`, `publicId`, `id`, `title`, `description`)
- **AND** the optional book metadata supported in this slice (`publication`, `publicationDate`, `start`, `end`, `readingStatus`, `use`, `boundary`) is documented consistently with the runtime validator
- **AND** `selectedQuote` is absent from the supported contract for this slice because notes carrying it are blocked

#### Scenario: Contract describes concepts/concept's optional translated fields
- **GIVEN** the exporter edition implements `concepts/concept`
- **WHEN** the publication contract is requested
- **THEN** the `concepts/concept` entry's required fields are exactly the shared identity fields (`publish`, `publicCollection`, `publicContentType`, `publicId`, `id`, `title`, `description`), matching `site/src/content.config.ts`'s declared `concepts` schema where `notThis`, `relations`, and `examples` all default to absent/empty
- **AND** the entry's optional fields document `notThis` as an optional non-blank string, `relations` as an optional list of `{name, relation}` entries, and `examples` as an optional list of non-blank strings
- **AND** its structured-body requirements remain empty

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
