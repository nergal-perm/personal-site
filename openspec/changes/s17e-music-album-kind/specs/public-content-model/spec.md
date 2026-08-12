## MODIFIED Requirements

### Requirement: PCM-02 Project only fields allowed by the publication kind

Each manifest entry SHALL contain common public fields plus the normalized fields defined for its publication kind, and SHALL exclude private and workflow-only metadata.

#### Scenario: Kind-specific projection succeeds
- **GIVEN** an admitted note satisfying its kind contract
- **WHEN** its manifest entry is built
- **THEN** the entry contains the kind's required public fields in canonical form
- **AND** it contains no undeclared private or workflow fields

#### Scenario: music/album projection contains its route, invariant metadata, and translated fields
- **GIVEN** an admitted `music/album` fixture satisfying its kind contract, with `artist`, `work`, `context`, and `association` always present, and optional `format`, `listenFor`, `care`, `releaseDate`, `genreTags`, `streamingUrl`, and `bandcampEmbedUrl` populated when authored
- **WHEN** its manifest entry is built
- **THEN** the entry uses the album route policy (`/music/{publicId}/` at the site-facing boundary)
- **AND** it contains the shared public fields plus the translated fields `context`, `association`, `format` (when authored), `care` (when authored), and `listenFor` (when authored) in the site's declared canonical form (`listenFor` as an ordered list of strings)
- **AND** it carries the invariant fields `artist`, `work`, `releaseDate`, `genreTags`, `streamingUrl`, `bandcampEmbedUrl`, and the kind's own literal `reviewType: album` marker in deterministic canonical form
- **AND** it contains no book-only, claim-only, note-only, essay-only, or concept-only fields, and no undeclared private or workflow fields

#### Scenario: Unsupported value reaches projection
- **GIVEN** a selected note with an unsupported field value or malformed structured body that escaped an earlier check
- **WHEN** manifest projection evaluates it
- **THEN** the whole manifest is blocked rather than emitting a partial entry

### Requirement: PCM-06 Keep English content structurally aligned and route-safe

An English candidate SHALL preserve invariant identity and structured fields, include neither missing nor extra translated fields, retain external URLs, and contain no internal Russian public routes.

#### Scenario: Structurally valid translation is checked
- **GIVEN** an English candidate with the same invariant fields and required structure as its Russian candidate
- **WHEN** translation validation runs
- **THEN** it is accepted if its source freshness and reference identities also match

#### Scenario: music/album English candidate keeps invariant metadata and translates prose and listenFor
- **GIVEN** a `music/album` Russian candidate with populated `context`, `association`, and its worker-produced English candidate, sharing the same identity, `artist`, `work`, `releaseDate`, `genreTags`, `streamingUrl`, and `bandcampEmbedUrl`, with optional `format`, `care`, and `listenFor` present on both sides only when they were authored
- **WHEN** translation validation runs
- **THEN** it is accepted only when the English candidate preserves the invariant fields exactly, keeps the same translated-field structure as the Russian candidate, and has the same number of `listenFor` entries in the same order when populated
- **AND** `context`, `association`, `format`, and `care`, when present, are validated as translated scalar fields rather than opaque copied metadata
- **AND** an English candidate that adds, removes, or reorders any `listenFor` entry, or that changes whether `format`/`care`/`listenFor` is present, is blocked as structurally misaligned
- **AND** any change to the invariant `artist`, `work`, `releaseDate`, `genreTags`, `streamingUrl`, or `bandcampEmbedUrl` metadata blocks the candidate as structurally misaligned

#### Scenario: music/album invariant metadata changes after approval or during translation
- **GIVEN** a `music/album` whose body, title, description, and translated fields are unchanged but whose `artist`, `work`, `releaseDate`, `genreTags`, `streamingUrl`, or `bandcampEmbedUrl` differ from its approved snapshot
- **WHEN** preparation evaluates the approved baseline
- **THEN** it creates a new candidate requiring review instead of mirroring the approved snapshot
- **AND** if the same invariant metadata changes while translation is in progress, preparation returns stale and installs no candidate

#### Scenario: Translation changes an invariant or route locale
- **GIVEN** an English candidate with altered identity, missing or duplicate fields, stale source provenance, or an internal `/ru/` route
- **WHEN** translation validation runs
- **THEN** it is blocked with field- or route-specific diagnostics
