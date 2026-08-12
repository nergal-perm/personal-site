## MODIFIED Requirements

### Requirement: PCM-02 Project only fields allowed by the publication kind

Each manifest entry SHALL contain common public fields plus the normalized fields defined for its publication kind, and SHALL exclude private and workflow-only metadata.

#### Scenario: Kind-specific projection succeeds
- **GIVEN** an admitted note satisfying its kind contract
- **WHEN** its manifest entry is built
- **THEN** the entry contains the kind's required public fields in canonical form
- **AND** it contains no undeclared private or workflow fields

#### Scenario: concepts/concept projection contains its route and any populated optional translated fields
- **GIVEN** an admitted `concepts/concept` fixture satisfying its kind contract, with optional `notThis`, zero or more `relations` entries, and zero or more `examples` entries populated on the source note
- **WHEN** its manifest entry is built
- **THEN** the entry uses the concept route policy (`/concepts/{publicId}/` at the site-facing boundary)
- **AND** it contains the shared public fields plus `notThis`, `relations`, and `examples` exactly when each was authored, each in the site's declared canonical form (`relations` as an ordered list of `{name, relation}`, `examples` as an ordered list of strings)
- **AND** a `concepts/concept` fixture with none of `notThis`/`relations`/`examples` populated projects an entry with the shared public fields only, matching `site/src/content.config.ts`'s declared optional/defaulted shape
- **AND** it contains no book-only, claim-only, note-only, or essay-only fields, and no undeclared private or workflow fields

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

#### Scenario: concepts/concept English candidate translates notThis, relations, and examples
- **GIVEN** a `concepts/concept` Russian candidate with a populated `notThis` string, one or more `relations` entries (each with a `name` and `relation`), and one or more `examples` entries, and its worker-produced English candidate
- **WHEN** translation validation runs
- **THEN** it is accepted only when the English candidate has the same `notThis` presence and the same number of `relations` and `examples` entries in the same order, with every entry's translated sub-fields present and non-blank
- **AND** an English candidate that adds, removes, or reorders any `relations` or `examples` entry, or that changes whether `notThis` is present, is blocked as structurally misaligned
- **AND** a `concepts/concept` fixture with none of `notThis`/`relations`/`examples` populated is validated using the same structural-alignment rule already proven for `blog/essay`, with no concept-specific exception
- **AND** whether an English entry's text actually differs from its Russian source is a translation-quality concern outside this requirement's structural-alignment boundary; this slice does not compare English values against Russian values for equality, for `concepts/concept` or any other kind

#### Scenario: Translation changes an invariant or route locale
- **GIVEN** an English candidate with altered identity, missing or duplicate fields, stale source provenance, or an internal `/ru/` route
- **WHEN** translation validation runs
- **THEN** it is blocked with field- or route-specific diagnostics
