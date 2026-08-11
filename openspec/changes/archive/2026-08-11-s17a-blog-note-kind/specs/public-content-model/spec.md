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

#### Scenario: Unsupported value reaches projection
- **GIVEN** a selected note with an unsupported field value or malformed structured body that escaped an earlier check
- **WHEN** manifest projection evaluates it
- **THEN** the whole manifest is blocked rather than emitting a partial entry

### Requirement: PCM-03 Resolve public links without leaking private topology

The exporter SHALL convert unambiguous links to selected public notes into public routes, convert links to private, unresolved, or ambiguous notes into visible plain labels, and block private transclusions. This slice recognizes `[[Target]]`, `[[Target|Alias]]`, and `[[Target#Heading]]` wikilink syntax (a heading fragment, if present, is dropped — resolution and labeling apply to `Target` only) and `![[Target]]` embed syntax. A resolved public route is locale-neutral (e.g. `/essays/{publicId}/` for `blog/essay`, `/notes/{publicId}/` for `blog/note`, with no `/ru/` or `/en/` locale segment) and is determined by the target note's own publication kind, not by the referrer's kind: resolution runs once on the Russian source ahead of translation, and the same route text is reused, untranslated, in the derived English candidate, so no locale segment can leak between candidates; final locale-prefixed site routing is a later concern outside this slice. An ambiguous target — one matching more than one known note — receives the same safe-label treatment as a private or unresolved target rather than a distinct diagnostic; disambiguating colliding note names is the author's responsibility, not a case the exporter blocks on. An embed target whose name has a recognized publishable-asset extension is not evaluated as a note transclusion — asset resolution is a separate requirement (PCM-05).

#### Scenario: Public target is unambiguous
- **GIVEN** a source link (`[[Target]]` or `[[Target|Alias]]`, with an optional `#Heading` fragment ignored) whose target text resolves uniquely to one selected public note among the known notes
- **WHEN** the source body is normalized
- **THEN** the output contains a locale-neutral public route for the target note (e.g. `/essays/{publicId}/`)
- **AND** it contains the authored alias, or the target text if no alias was given, as the display label

#### Scenario: A link to a blog/note target resolves to the note route, not the essay route
- **GIVEN** a vault with an admitted `blog/note` target and a referring note whose body links to it by filename stem
- **WHEN** the referring note is prepared
- **THEN** the link resolves to the note's own route (`/notes/{publicId}/`), not the `/essays/{publicId}/` route used for `blog/essay` targets

#### Scenario: Private, unresolved, or ambiguous target is linked
- **GIVEN** a source link whose target text is private, matches no known note, or matches more than one known note
- **WHEN** the source body is normalized
- **THEN** the output retains a human-readable label — the authored alias, or the target text if no alias was given
- **AND** it contains no vault path, private route, source identifier, or Obsidian link token

#### Scenario: Private target is transcluded
- **GIVEN** a source note transcludes (`![[Target]]`) content from a note that is private, unresolved, or ambiguous among the known notes
- **WHEN** the source body is normalized
- **THEN** normalization is blocked with a transclusion diagnostic
- **AND** no candidate is installed or replaced

#### Scenario: Embed target is a publishable asset, not a note
- **GIVEN** a source note embeds (`![[Target]]`) a target whose name has a recognized publishable-asset extension
- **WHEN** the source body is normalized
- **THEN** the embed is left untouched by this requirement — it is neither resolved to a route nor blocked as a transclusion

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

#### Scenario: Translation changes an invariant or route locale
- **GIVEN** an English candidate with altered identity, missing or duplicate fields, stale source provenance, or an internal `/ru/` route
- **WHEN** translation validation runs
- **THEN** it is blocked with field- or route-specific diagnostics
