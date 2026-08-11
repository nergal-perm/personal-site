## MODIFIED Requirements

### Requirement: PCM-02 Project only fields allowed by the publication kind

Each manifest entry SHALL contain common public fields plus the normalized fields defined for its publication kind, and SHALL exclude private and workflow-only metadata.

#### Scenario: Kind-specific projection succeeds
- **GIVEN** an admitted note satisfying its kind contract
- **WHEN** its manifest entry is built
- **THEN** the entry contains the kind's required public fields in canonical form
- **AND** it contains no undeclared private or workflow fields

#### Scenario: blog/note projection contains only shared fields, no kind-specific extension
- **GIVEN** an admitted `blog/note` fixture satisfying its kind contract (identity, title, description; no structured body)
- **WHEN** its manifest entry is built
- **THEN** the entry contains the same shared public fields as a `blog/essay` entry (identity, title, description)
- **AND** it contains no essay-only fields (e.g. sections, abstract, closing) and no undeclared private or workflow fields

#### Scenario: blog/claim projection contains its own required and optional fields
- **GIVEN** an admitted `blog/claim` fixture satisfying its kind contract (identity, title, description, a non-blank `statement`; zero or more populated relationship arrays and `sources` entries)
- **WHEN** its manifest entry is built
- **THEN** the entry contains the shared public fields (identity, title, description) plus `statement` and whichever `supports`/`opposes`/`assumes`/`refines`/`contradicts`/`sources` entries were populated on the source note
- **AND** it contains no essay-only or note-only fields, and no undeclared private or workflow fields
- **AND** every relationship `target` value is projected unchanged, as opaque text — this requirement does not resolve, validate, or route it against any other publication (deferred; see `openspec/implementation-plan.md`'s SEM-01/SEM-02 semantic-reference slices)

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

#### Scenario: blog/note English candidate is checked the same way as blog/essay
- **GIVEN** a `blog/note` Russian candidate and its worker-produced English candidate, sharing the same identity, title, and description structure
- **WHEN** translation validation runs
- **THEN** it is accepted using the same structural-alignment rule already proven for `blog/essay`, with no note-specific exception

#### Scenario: blog/claim English candidate is checked the same way as blog/essay
- **GIVEN** a `blog/claim` Russian candidate and its worker-produced English candidate, sharing the same identity, title, description, and `statement` structure, with byte-identical relationship-array (`supports`/`opposes`/`assumes`/`refines`/`contradicts`) and `sources` entries in the same order
- **WHEN** translation validation runs
- **THEN** it is accepted using the same structural-alignment rule already proven for `blog/essay`, with no claim-specific exception
- **AND** the relationship arrays and `sources` are not machine-translated in this slice — both candidates carry the same `label`/`target`/other entry values, mirroring how `blog/essay`'s own `sources` field is not yet translated or projected either
- **AND** an English candidate with altered relationship-array or `sources` values, count, or order is blocked, since none of that data is expected to differ between the Russian source and its English candidate in this slice

#### Scenario: Translation changes an invariant or route locale
- **GIVEN** an English candidate with altered identity, missing or duplicate fields, stale source provenance, or an internal `/ru/` route
- **WHEN** translation validation runs
- **THEN** it is blocked with field- or route-specific diagnostics
