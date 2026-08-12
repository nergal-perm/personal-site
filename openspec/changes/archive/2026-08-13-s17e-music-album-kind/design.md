## Context

`music/album` is the third kind (after S17c's `bibliography/book` and S17d's `concepts/concept`) to need a mix of translated and invariant fields, and the second to need a translated list. Every technical primitive this slice needs already exists and is already proven:

- `FieldContract.nonBlank(name)` and `FieldContract.nonBlankStringList(name)` (S17c) cover every field shape this kind needs — no new `FieldContract.Type` is required, unlike S17d's `STRUCTURED_LIST` addition for `relations`' `{name, relation}` shape. `music/album` has no map-shaped field.
- `MarkdownNote.listOfScalars(key)` (S17c) already reads an invariant string list from frontmatter (used for book's `authors`); the same reader covers `genreTags` here.
- S17d's synthetic-key flattening pattern (`relations[i].name`, `examples[i]`) already proves how to carry a translated list through the existing `PublicField`/translation pipeline with zero pipeline changes, and `BracketIndexedFields` (already generic, kind-agnostic, keyed on key shape not kind identity) already reassembles any kind's bracket-indexed fields into site YAML with zero changes needed here.

The one genuinely new element is `reviewType`: the site's `music` collection schema uses `reviewType: z.enum(['album', 'track'])` as its own kind discriminator, distinct from the exporter's generic `contentType` field that `FilesystemManagedSiteInstaller` already always writes from `identity.publicContentType()`. Unlike every other album field, `reviewType` has no vault-source counterpart to read or validate — its value is always the literal `"album"` for this kind, known from which `PublicationKind` matched, not authored by the note.

## Goals / Non-Goals

**Goals:**

- Add `AlbumPublicationKind` for `music/album` through the existing `PublicationKind` seam, with `/music/` route ownership.
- Translate `title`, `description`, `context`, `association`, `format`, and `care` through the existing, unmodified `PublicField` scalar path.
- Translate `listenFor` by copying S17d's synthetic-key flattening pattern directly into `AlbumPublicationKind`, kept private to this kind per the collaborative-design decision to defer extraction until a third kind needs a translated list.
- Carry `artist`, `work`, `releaseDate`, `genreTags`, `streamingUrl`, and `bandcampEmbedUrl` as invariant `structuredData`, following `BookPublicationKind`'s exact established pattern (`listOfScalars` for the list field, per-field optional-scalar emission for the rest).
- Emit the literal `reviewType: "album"` invariant marker unconditionally, with no corresponding admission read or validation.

**Non-Goals:**

- No extraction of a shared translated-list mechanism this slice — `ConceptPublicationKind` and `AlbumPublicationKind` each keep their own private flattening logic. Revisit only if a third kind needs the same shape (per the collaborative-design decision).
- No `music/track` support (the schema's other `reviewType` value) — out of scope, a separate future kind.
- No new `FieldContract` type or `MarkdownNote` parser method — every field shape this kind needs is already covered by S17c/S17d primitives.
- No changes to `exporter-java/` or `site/`; both remain evidence sources only.

## Decisions

### D1 — `AlbumPublicationKind` owns `music/album` and `/music/`

Add `AlbumPublicationKind implements PublicationKind` with:

- `collection() -> "music"`
- `contentType() -> "album"`
- `routePrefix() -> "music"`

`PublicationKinds.installed()` gains one explicit registration line, matching S17a–S17d's precedent.

### D2 — Field posture, decided against the live fixture pair's real evidence

Translated (via `PublicField`, scalar path, unmodified):

- `title`, `description` (shared identity, existing pattern)
- `context`, `association` (always required, translated prose)
- `format`, `care` (optional, translated prose when authored)

Translated (via S17d's private flattening pattern, copied not shared):

- `listenFor[i]` (optional list of translated prose entries)

Invariant (via `structuredData`, `BookPublicationKind`'s exact established pattern):

- `artist`, `work` (always required, proper nouns — byte-identical RU/EN in the live fixture)
- `releaseDate`, `streamingUrl`, `bandcampEmbedUrl` (optional scalars)
- `genreTags` (optional list of scalars, via `listOfScalars` — same reader as book's `authors`, but optional rather than required)
- `reviewType` (always the literal `"album"`, no vault-source counterpart — see D3)

Alternative considered: treat `genreTags` as translated, matching `listenFor`. Rejected per the collaborative-design decision — no fixture evidence supports it, and genre tags read as a controlled-vocabulary list (like `topics`/`tags` on every kind, never translated) rather than authored prose.

### D3 — `reviewType` is a kind-owned literal with no admission read

`AlbumPublicationKind.admit()` never calls `frontmatter.string("reviewType")` or validates anything named `reviewType` — the field does not need to exist in vault frontmatter at all, and if a note happens to carry one, it is ignored (harmless, not blocked; per-kind fields on the source note beyond what this exporter reads are not this requirement's concern). `structuredDataFrom()` unconditionally appends `reviewType: "album"\n`, mirroring how the shared `FilesystemManagedSiteInstaller.frontmatter()` already unconditionally writes `contentType` from `identity.publicContentType()` — the difference is only that the site schema wants this specific value under a second, kind-chosen field name that the generic installer does not know about, so the kind supplies it directly through its own invariant channel instead.

Alternative considered: derive `reviewType` generically from `identity.publicContentType()` inside the shared installer, keyed by a naming convention. Rejected — this would require the shared, kind-agnostic installer to learn a `music`-specific field-name mapping, exactly the collection/content-type conditional the plan's kind-neutral lifecycle rule forbids in generic orchestration. A one-line literal inside the one kind that needs it is smaller and keeps the installer generic.

### D4 — No new `FieldContract` type; `genreTags` reuses `nonBlankStringList` as optional

`examples` (S17d) already proved `nonBlankStringList` used as an *optional* field (not just book's required `authors`). `genreTags` follows the identical pattern: `FieldContract.nonBlankStringList("genreTags")` placed among `AlbumPublicationKind`'s optional fields. `listenFor` is a translated field, not represented in the contract as an invariant list at all — its contract representation is `FieldContract.nonBlankStringList("listenFor")` too, since the *contract* only describes the RU source frontmatter's required shape (a non-blank string list), not whether the pipeline later translates or preserves it verbatim; translated-vs-invariant is a runtime/design concern, not a contract-shape concern (this mirrors S17d's `examples`, which is also contract-typed as `nonBlankStringList` despite being translated).

## Risks / Trade-offs

- [Risk] Copying S17d's flattening pattern into `AlbumPublicationKind` creates near-duplicate code across two kinds. → Mitigation: deliberate, per the collaborative-design decision; the duplication is small (~15-20 lines), and premature sharing after only two data points risks generalizing the wrong shape. Revisit at a third kind.
- [Risk] `reviewType`'s no-vault-read design means a note author cannot see or control it — if the site schema ever needs a real per-note `reviewType` value (e.g. once `music/track` exists and both share one collection), this slice's assumption (always `"album"` for this kind) would need revisiting. → Mitigation: explicitly scoped as this slice's assumption in this document; `music/track` is a separate future kind that would supply its own literal the same way.
- [Risk] `format`/`care` being optional-and-translated means an English candidate could plausibly omit them even when the Russian source has them, if the worker drops a field. → Mitigation: already covered by `EnglishCandidateValidator`'s existing, unmodified structural-alignment check (same key-list-equality mechanism proven for every prior kind).

## Migration Plan

1. Add `AlbumPublicationKind` (admission, translated-field construction including the copied flattening pattern for `listenFor`, invariant `structuredData` construction including `genreTags` and the literal `reviewType`) and register it in `PublicationKinds.installed()`.
2. Add or update fixtures/tests proving `music/album` reuses the existing translation and structural-alignment path with no kind-specific exception, and that invariant metadata changes correctly force review/stale, matching `bibliography/book`'s established precedent.
3. Add one full `music/album` acceptance fixture (admit → prepare → approve → build-from-review → install-to-site) covering at least one populated `format`, `care`, two `listenFor` entries, and populated `genreTags`, then keep the existing suite green.
4. Extend `write-publication-contract` CLI coverage so the emitted contract includes `music/album`.

Rollback is a plain revert. The slice adds one new kind with no new shared-carrier changes — no irreversible migration or external state change.

## Open Questions

None. The two material ambiguities (translated-vs-invariant posture for every field, and whether to extract the translated-list mechanism now) were resolved in the functional collaborative-design pass.
