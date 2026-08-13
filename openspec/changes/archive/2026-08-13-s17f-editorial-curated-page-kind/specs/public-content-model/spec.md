## MODIFIED Requirements

### Requirement: PCM-02 Project only fields allowed by the publication kind

Each manifest entry SHALL contain common public fields plus the normalized fields defined for its publication kind, and SHALL exclude private and workflow-only metadata.

#### Scenario: Kind-specific projection succeeds
- **GIVEN** an admitted note satisfying its kind contract
- **WHEN** its manifest entry is built
- **THEN** the entry contains the kind's required public fields in canonical form
- **AND** it contains no undeclared private or workflow fields

#### Scenario: editorial/curated_page (about) projects to a JSON page artifact, not a Markdown content-collection entry
- **GIVEN** an admitted `editorial/curated_page` (`about`) fixture satisfying its kind contract
- **WHEN** its manifest entry and release artifact are built
- **THEN** the entry uses the curated-page route policy (`/{locale}/about/`, the site's dedicated non-`src/content` route)
- **AND** the release artifact is one JSON document per locale at `src/data/pages/{locale}/about.json`, field-compatible with the shape the site's `registry.ts` `fromPage()` already reads (`id`, `type`, `language`, `title`, `summary`, `searchable`, plus this kind's own `eyebrow`, `lead`, `principles`, `colophon`), not a Markdown file with YAML frontmatter — `topics` and `links` are omitted, matching every other implemented kind in this exporter edition, none of which populate those fields yet (`registry.ts`'s `fromPage()` and `fromContent()` already default both to `[]` when absent, so omission is safe for the site build)
- **AND** it contains no book-only, claim-only, note-only, essay-only, album-only, or concept-only fields, and no undeclared private or workflow fields

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

#### Scenario: editorial/curated_page (about) English candidate translates prose and principle titles, keeps invariant metadata
- **GIVEN** an `editorial/curated_page` (`about`) Russian candidate with its `summary`, `eyebrow`, `lead`, `colophon`, and an ordered list of `principles` (each a title/text pair), and its worker-produced English candidate sharing the same identity and `searchable`
- **WHEN** translation validation runs
- **THEN** it is accepted only when the English candidate preserves `searchable` exactly, and has the same number of `principles` entries in the same order
- **AND** `summary`, `eyebrow`, `lead`, `colophon`, and both the title and text of every `principles` entry are validated as translated prose fields, not opaque copied metadata
- **AND** an English candidate that adds, removes, or reorders any `principles` entry is blocked as structurally misaligned

#### Scenario: Translation changes an invariant or route locale
- **GIVEN** an English candidate with altered identity, missing or duplicate fields, stale source provenance, or an internal `/ru/` route
- **WHEN** translation validation runs
- **THEN** it is blocked with field- or route-specific diagnostics
