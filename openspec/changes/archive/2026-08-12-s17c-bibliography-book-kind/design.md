## Context

The current exporter already includes the claim-era generalization from fixed `(title, description)` pairs to ordered translated `PublicField` values plus one opaque `structuredData` fragment. `PublicationKinds.installed()` selects kind objects, `PublicNoteIndex` derives public routes from `kind().routePrefix()`, `PrepareHandler` fingerprints `(body, PublicField...)` for translation freshness, and `CandidateSnapshot`/`ReferenceMap` already hash `structuredData` separately from translated fields.

That seam is enough for another kind only if the source contract fits one of the two existing carrier shapes:

- translated scalar text in the ordered `PublicField` list;
- invariant or untranslated metadata in `structuredData`.

`bibliography/book` is the first kind that asks for a required list field (`authors`) and a mixed set of optional metadata:

- invariant metadata: `authors`, `publication`, `publicationDate`, `start`, `end`, `readingStatus`;
- translated scalar metadata: `use`, `boundary`;
- mixed structured metadata: `selectedQuote.kind`, `selectedQuote.text`, `selectedQuote.locator`.

The live site already publishes books under `/library/` and renders those fields in [BookPage.astro](/Users/eugene/Dev/personal-site/site/src/views/BookPage.astro). The current exporter still blocks `bibliography/book` at kind lookup. `FieldContract` only models `BOOLEAN` and `STRING`, and `MarkdownNote` exposes scalar strings plus list-of-maps/opaque YAML, but not a list of strings. Those are the narrow technical gaps this slice must close.

## Goals / Non-Goals

**Goals:**

- Add a `BookPublicationKind` for `bibliography/book` through the existing `PublicationKind` seam, with `/library/` route ownership.
- Support a required non-empty `authors` list in runtime validation and publication-contract output.
- Preserve the claim-era split between translated `PublicField` values and invariant `structuredData` instead of reopening the whole pipeline.
- Treat `use` and `boundary` as translated scalar book fields.
- Treat `authors`, `publication`, `publicationDate`, `start`, `end`, and `readingStatus` as invariant book metadata that participates in approved-baseline matching and stale-translation detection.
- Keep the existing blog kinds and their acceptance tests unchanged.

**Non-Goals:**

- No new generic metadata framework, reflection-based kind loading, or new collection/content-type conditionals in generic orchestration.
- No second whole-pipeline carrier redesign after S17b.
- No support for `selectedQuote` in this slice.
- No body-section extraction rule specific to bibliography; the current normalized body path remains unchanged.
- No changes to `exporter-java/`; it remains an evidence source only.

## Decisions

### D1 — `BookPublicationKind` owns `bibliography/book` and `/library/`

Add `BookPublicationKind implements PublicationKind` with:

- `collection() -> "bibliography"`
- `contentType() -> "book"`
- `routePrefix() -> "library"`

`PublicationKinds.installed()` gains one explicit registration line. `PublicNoteIndex`, `InspectPublicationHandler`, `PrepareHandler`, `MarkReviewedHandler`, and site installation already use the `PublicationKind` object returned by intake, so no new generic branching is needed.

Alternative considered: teach generic routing code about `publicCollection == bibliography` or `publicContentType == book`. Rejected because S17a already extracted kind-owned route policy precisely to avoid collection/type conditionals in orchestration code.

### D2 — Add one narrow source/contract primitive: required string-list fields

Extend the scalar-first admission surface just enough for `authors`:

- `MarkdownNote` gets a `listOfScalars(String key)` reader for frontmatter lists whose items are scalar strings.
- `FieldContract.Type` gains `STRING_LIST`.
- `FieldContract` gains a named constructor for a non-empty list of non-blank strings, and the contract writer/reader keeps serializing deterministically.
- Contract/runtime conformance tests learn how to evaluate that field type.

This is intentionally narrower than a generic “any YAML type” contract system. `bibliography/book` is the first implemented kind that genuinely needs a required list field; nothing else in this slice needs arbitrary nested objects in the contract.

Alternative considered: accept only a scalar `author` field and normalize it to a one-entry list. Rejected because the live site schema already treats `authors` as canonical, and baking a legacy scalar alias into the new exporter would spend complexity without reducing current slice risk.

### D3 — Split book metadata by translation posture, not by frontmatter convenience

`BookPublicationKind.admit()` produces:

- translated `PublicField`s in canonical order:
  - always `title`
  - always `description`
  - optional `use`
  - optional `boundary`
- invariant `structuredData` YAML containing:
  - `authors`
  - `publication`
  - `publicationDate`
  - `start`
  - `end`
  - `readingStatus`

This preserves the current pipeline contract:

- translated scalar text stays in `PublicField`, so `TranslationJob`, `TranslationWorker`, `EnglishCandidateValidator`, and freshness matching continue to work through their existing generalized list path;
- invariant metadata stays opaque but hashed through `CandidateSnapshot.structuredData()` and `ReferenceMap.structuredDataHash`, so baseline matching and stale detection already cover it.

The YAML fragment is rendered by the kind, not by shared orchestration. `FilesystemManagedSiteInstaller` continues to emit generic scalar `PublicField` lines and append `structuredData` verbatim before the closing frontmatter delimiter.

Alternative considered: put every optional book field into `structuredData`. Rejected because `use` and `boundary` are authored prose rendered on the public page and should travel through the translated-field path, not be copied unchanged from Russian into English.

Alternative considered: add a second “invariant public field” carrier beside `PublicField` and `structuredData`. Rejected because the current `structuredData` fragment already has the right freshness/install semantics, and another carrier would broaden the pipeline for only one slice.

### D4 — `selectedQuote` is a hard block, not a silent partial implementation

`selectedQuote` is the first book field that mixes invariant structure with translated nested prose:

- invariant: `kind`, `locator`
- translated: `text`

The current pipeline has no mixed translated-structured carrier. This slice therefore blocks any `bibliography/book` note carrying `selectedQuote` during admission with a diagnostic that names the field and states that mixed translated structured quote metadata is unsupported in this slice.

Alternative considered: pass `selectedQuote` through `structuredData` unchanged. Rejected because it would copy Russian-only quote text into English output while still claiming a structurally valid English candidate.

Alternative considered: introduce a new generic nested translation carrier now. Rejected because it would broaden the pipeline beyond the smallest slice needed for the first `bibliography/book` result and would front-run future kinds without evidence.

### D5 — The acceptance slice uses full-body normalization with no bibliography-only extraction rule

The current exporter normalizes and translates the note body as authored. Unlike the legacy exporter, this slice does not introduce a book-specific “only publish the Конспект section” rule. That behavior is neither required by the live site schema nor already present in the current exporter.

This keeps the slice focused on kind admission, route ownership, frontmatter projection, and translation alignment. A future body-shaping rule would need its own evidence and spec delta.

Alternative considered: port exporter-java’s bibliography body extraction rule into this slice. Rejected because it would couple `s17c` to legacy behavior without a current-spec requirement and would widen the blast radius into body semantics unnecessarily.

## Risks / Trade-offs

- [Risk] `FieldContract.Type.STRING_LIST` is the first public-contract type beyond scalar string/boolean, so a partial implementation could let contract writing and runtime validation diverge.  
  → Mitigation: extend the shared contract/runtime conformance harness in the same slice and keep `bibliography/book` fixtures in the same table as kind validation tests.

- [Risk] Using `structuredData` for invariant book metadata makes deterministic YAML rendering part of the correctness boundary.  
  → Mitigation: keep one deterministic emission order in `BookPublicationKind`, reuse the shared scalar-escaping helper, and assert the installed frontmatter text in the end-to-end acceptance test.

- [Risk] Blocking `selectedQuote` leaves part of the live site schema unsupported for books after this slice.  
  → Mitigation: make the block explicit in the spec, contract, and admission diagnostics so the limitation is visible and cannot silently corrupt EN output.

- [Risk] `MarkdownNote.listOfScalars()` is a parser expansion and could accidentally loosen unrelated frontmatter handling.  
  → Mitigation: keep it additive, constrained to explicit callers, and back it with parser-focused tests for absent, empty, blank, mixed-type, and malformed lists.

## Migration Plan

1. Extend the admission/contract surface with the new string-list field type and `MarkdownNote.listOfScalars()`.
2. Add `BookPublicationKind` and register it in `PublicationKinds.installed()`.
3. Carry translated scalar book fields (`use`, `boundary`) through the existing `PublicField` path and invariant book metadata through `structuredData`.
4. Update contract writing/conformance, route generation, and site installation assertions.
5. Add one full acceptance fixture for `bibliography/book`, then keep the existing suite green.

Rollback is a plain revert. The slice adds a new kind and parser/contract behavior but no irreversible migration or external state change.

## Open Questions

None. The slice intentionally resolves the only material ambiguity by blocking `selectedQuote` until the exporter has a real mixed translated-structured carrier.
