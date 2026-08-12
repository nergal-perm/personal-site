# Public content model Specification

## Purpose

Define deterministic, safe, kind-aware transformation from admitted Obsidian Markdown to the public manifest consumed by review and release. Evidence: E-CONTENT and E-ADM in `openspec/requirements-baseline.md`.
## Requirements
### Requirement: PCM-01 Produce a deterministic normalized manifest

The exporter SHALL normalize admitted notes into a deterministic manifest whose ordering, route derivation, metadata projection, body projection, and hashes depend only on declared source inputs.

#### Scenario: Same inputs are built twice
- **GIVEN** identical source-note bytes, resolved dependency bytes, and exporter contract edition
- **WHEN** the public manifest is built twice in different filesystem enumeration orders
- **THEN** the normalized entries, ordering, routes, and hashes are identical

#### Scenario: Workflow metadata changes
- **GIVEN** only exporter-owned workflow frontmatter fields differ between two otherwise identical source notes
- **WHEN** each note is normalized
- **THEN** their public content and content hashes are identical

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
- **AND** each populated `sources` entry conforms to the site's declared `claimSource`/`claimReference`/rich-text token shape before the opaque fragment can reach installation
- **AND** every relationship `target` value is projected unchanged, as opaque text — this requirement does not resolve, validate, or route it against any other publication (deferred; see `openspec/implementation-plan.md`'s SEM-01/SEM-02 semantic-reference slices)

#### Scenario: bibliography/book projection contains its route, invariant metadata, and translated scalar book fields
- **GIVEN** an admitted `bibliography/book` fixture satisfying its kind contract, with at least one author and optional `publication`, `publicationDate`, `start`, `end`, `readingStatus`, `use`, or `boundary` metadata
- **WHEN** its manifest entry is built
- **THEN** the entry uses the book route policy (`/library/{publicId}/` at the site-facing boundary)
- **AND** it contains the shared public fields plus the translated scalar book fields `use` and `boundary` when they were authored
- **AND** it carries `authors`, `publication`, `publicationDate`, `start`, `end`, and `readingStatus` in deterministic canonical form when they were authored
- **AND** it contains no claim-only, note-only, or essay-only fields, and no undeclared private or workflow fields

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

### Requirement: PCM-04 Preserve protected Markdown and remove Obsidian-only syntax

The exporter SHALL leave code, inline code, and other declared protected regions semantically unchanged while removing Obsidian comments and transforming only eligible Markdown constructs. Protected regions for this requirement are fenced code blocks and inline code spans; other constructs are added to the protected-region set only when a future slice's requirements need them. An Obsidian comment with no matching closing marker SHALL block preparation with a diagnostic rather than being treated as extending to the end of the source, so an author's forgotten closing marker cannot silently drop the remainder of an article from public content.

#### Scenario: Link-like text appears in protected content
- **GIVEN** code or another protected region containing wiki-link, embed, or reference-like text
- **WHEN** Markdown normalization runs
- **THEN** the protected text remains unchanged

#### Scenario: Obsidian comment appears in publishable prose
- **GIVEN** an admitted source body containing an Obsidian comment
- **WHEN** Markdown normalization runs
- **THEN** the comment is absent from public content

#### Scenario: Obsidian comment is left unclosed
- **GIVEN** an admitted source body containing an opening Obsidian comment marker with no matching closing marker
- **WHEN** Markdown normalization runs
- **THEN** preparation blocks with a diagnostic identifying the unclosed comment
- **AND** no candidate, approved snapshot, or workflow state is created or changed

### Requirement: PCM-05 Resolve assets safely and content-address them

The exporter SHALL resolve referenced publishable assets within the vault, prefer an exact vault-relative match over basename fallback, require basename fallback to be unique, and materialize each accepted asset under a deterministic content-derived name. Resolution runs on the asset-like embed targets (`![[Target]]` with a recognized publishable-asset extension) that PCM-03's link/transclusion resolution step identifies but does not itself resolve. An accepted asset's public reference is a content-addressed path under the vault-asset public route already reserved for this purpose in the site's managed-content contract (`public/assets/vault/`), built from the asset's SHA-256 content hash and a normalized extension (`.jpeg` folds to `.jpg`). The rewritten Markdown for every accepted asset — image, audio, or video alike — is a Markdown image/link reference (`![label](path)`) to that public asset; this requirement does not prescribe type-specific HTML rendering (e.g. `<audio>`/`<video>` tags) for any asset type.

#### Scenario: Exact asset path exists
- **GIVEN** an image, audio, or video reference with an exact safe vault-relative target
- **WHEN** assets are resolved
- **THEN** that target is selected even if another file has the same basename
- **AND** public Markdown refers to its content-addressed public asset as a Markdown image/link reference

#### Scenario: Basename is ambiguous or unsafe
- **GIVEN** no exact target and multiple basename matches, or a target that escapes through traversal or symlink
- **WHEN** assets are resolved
- **THEN** publication is blocked with an asset diagnostic
- **AND** no candidate is installed or replaced

#### Scenario: Identical bytes are referenced more than once
- **GIVEN** multiple accepted references to identical asset bytes
- **WHEN** assets are materialized
- **THEN** one deterministic public asset is emitted and all references use it

---

**Exclusions and unresolved choices for this slice (not normative):**
- Type-specific asset rendering (HTML5 `<audio controls>`/`<video controls>` tags, numeric-alias-as-width sizing) is out of scope. Every accepted asset — regardless of extension — resolves to a uniform Markdown image/link reference. This was an explicit design decision (favoring PCM-05's literal, spec-minimal text over exporter-java's richer legacy rendering) made during this slice's collaborative-design pass, not a baseline requirement change.
- This slice materializes an accepted asset into the candidate workspace only. Whether/how a materialized asset travels from candidate to approved snapshot and release output is unresolved here — approved-snapshot and release-materialization code paths are untouched by this slice (confirmed: neither `ReleaseOutputStore` nor `BuildFromReviewHandler` reference assets today).
- Asset variants, image optimization, remote (non-vault) assets, and media types beyond the existing recognized publishable-asset extension set are out of scope.
- The precise collaborator boundary (in-memory asset bytes first, a real vault/file adapter proven against the same contract second, per this project's outside-in slicing discipline) is a technical-design concern, not a functional one — resolved in `design.md`.

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

#### Scenario: bibliography/book English candidate keeps invariant book metadata and translates scalar book notes
- **GIVEN** a `bibliography/book` Russian candidate and its worker-produced English candidate, sharing the same identity, `authors`, `publication`, `publicationDate`, `start`, `end`, and `readingStatus`, with optional `use` and `boundary` values present on both sides only when they were authored
- **WHEN** translation validation runs
- **THEN** it is accepted only when the English candidate preserves the invariant book metadata exactly and keeps the same translated-field structure as the Russian candidate
- **AND** `use` and `boundary`, when present, are validated as translated scalar fields rather than opaque copied metadata
- **AND** any change to the invariant author or publication metadata blocks the candidate as structurally misaligned

#### Scenario: bibliography/book invariant metadata changes after approval or during translation
- **GIVEN** a `bibliography/book` whose body, title, description, `use`, and `boundary` are unchanged but whose `authors`, `publication`, `publicationDate`, `start`, `end`, or `readingStatus` differ from its approved snapshot
- **WHEN** preparation evaluates the approved baseline
- **THEN** it creates a new candidate requiring review instead of mirroring the approved snapshot
- **AND** if the same invariant metadata changes while translation is in progress, preparation returns stale and installs no candidate

#### Scenario: Translation changes an invariant or route locale
- **GIVEN** an English candidate with altered identity, missing or duplicate fields, stale source provenance, or an internal `/ru/` route
- **WHEN** translation validation runs
- **THEN** it is blocked with field- or route-specific diagnostics
