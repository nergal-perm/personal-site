## Context

The current exporter's translated-field carrier, `PublicField`, is an ordered list of scalar `(key, value)` string pairs (`publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/PublicField.java`). `PrepareHandler` sends a note's `PublicField` list plus its body to `TranslationWorker`; the worker returns an English `PublicField` list with the same keys. `EnglishCandidateValidator.translatedFieldStructureDiagnostics()` already asserts the English candidate's field keys equal the Russian candidate's field keys, in the same order — this check is kind-agnostic and untouched by any kind added so far. `CandidateSnapshot` carries `ruFields`/`enFields` as genuinely separate per-language lists, and `FilesystemManagedSiteInstaller.frontmatter()` renders whichever language's field list applies as flat `key: "value"` YAML scalar lines — one line per field, with no kind-awareness. `structuredData`, by contrast, is a single opaque YAML fragment stored once on `AdmittedPublication`/`CandidateSnapshot` and appended verbatim to *both* the RU and EN installed files (proven by `bibliography/book`'s invariant author/publication metadata, S17c) — it has no per-language variant.

`concepts/concept` needs three optional fields projected onto the live site (`site/src/content.config.ts`, `ConceptPage.astro`): `notThis` (translated prose scalar), `relations` (translated list of `{name, relation}` prose pairs), and `examples` (translated list of prose strings). Per the prior collaborative-design decision, all three are machine-translated RU→EN, not left invariant or blocked. `notThis` fits the existing `PublicField` shape exactly (same pattern as book's `use`/`boundary`, S17c). `relations` and `examples` do not: neither `PublicField` (scalar-only) nor `structuredData` (single-language-only) can carry a translated, per-language, list-shaped value today. This is the one genuine carrier gap this slice closes.

## Goals / Non-Goals

**Goals:**

- Add `ConceptPublicationKind` for `concepts/concept` through the existing `PublicationKind` seam, with `/concepts/` route ownership.
- Translate `notThis` through the existing, unmodified `PublicField` scalar path.
- Translate `relations` and `examples` by flattening each list into synthetic-keyed `PublicField` entries at admission, so the *entire* translation pipeline (job payload, worker protocol, freshness hashing, `EnglishCandidateValidator`'s structural check) requires zero changes — it already treats `PublicField` as an ordered flat list of translatable strings.
- Reassemble the flattened entries back into the site's declared YAML list shape at the one place that actually needs list structure: `FilesystemManagedSiteInstaller`'s frontmatter emission — via a small, kind-agnostic bracket-key grouping convention, not a kind-aware branch.
- Keep the flattening/reassembly key convention itself private to this slice's concrete classes; do not extract a shared `TranslatedListField` abstraction until a second kind (e.g. a future `music/album` `listenFor` requirement) actually needs one.
- Extend `FieldContract` with exactly the one new shape `relations` requires; reuse `nonBlankStringList` (added in S17c) for `examples`, now used as an *optional* field for the first time.

**Non-Goals:**

- No general "English value must differ from Russian value" translation-quality check, for `concepts/concept` or any other kind. This slice delivers count/order/key-structure alignment only (already implied by the flattening approach), consistent with the descoped `PCM-06` spec scenario.
- No shared, reusable "translated list" domain type. `ConceptPublicationKind` owns its own flattening/unflattening; the installer's key-grouping convention is generic YAML-emission mechanics, not a new domain abstraction.
- No structured review/inspect-side rendering of `relations`/`examples` beyond what the existing generic candidate-display path already provides for any `PublicField` list.
- No changes to `exporter-java/` or `site/`; both remain evidence sources only.

## Decisions

### D1 — `ConceptPublicationKind` owns `concepts/concept` and `/concepts/`

Add `ConceptPublicationKind implements PublicationKind` with:

- `collection() -> "concepts"`
- `contentType() -> "concept"`
- `routePrefix() -> "concepts"`

`PublicationKinds.installed()` gains one explicit registration line, matching S17a–S17c's precedent. No generic orchestration code (`PublicNoteIndex`, `InspectPublicationHandler`, `PrepareHandler`, `MarkReviewedHandler`, site installation) needs new branching — all already dispatch through the `PublicationKind` object returned by intake.

### D2 — `relations`/`examples` are flattened into synthetic-keyed `PublicField` entries at admission

`ConceptPublicationKind.admit()` builds the translated `PublicField` list in this deterministic order:

```
("title", <title>)
("description", <description>)
("notThis", <notThis>)                      -- only if authored
("relations[0].name", <name>)               -- one pair per relations entry, in source order
("relations[0].relation", <relation>)
("relations[1].name", ...)
("relations[1].relation", ...)
("examples[0]", <example>)                  -- one entry per examples item, in source order
("examples[1]", ...)
```

This list *is* the RU candidate's field list (used as-is, per how every existing kind already reuses `AdmittedPublication.fields()` as `ruFields`). `PrepareHandler` sends it to the translation worker unchanged; the worker returns an English list with the same keys, in the same order, each value translated. `EnglishCandidateValidator.translatedFieldStructureDiagnostics()` already rejects any English list whose keys don't exactly match — so a dropped, added, or reordered `relations`/`examples` entry is already blocked with zero new validator code.

Key format: `<field>[<index>]` for scalar list items, `<field>[<index>].<subfield>` for structured list items — a plain, greppable convention with no delimiter collision risk against any currently-declared frontmatter key.

Alternative considered: give `PublicField` a `List<String> pathSegments()` or similar structured key instead of a plain string. Rejected — it would touch every existing `PublicField` consumer (equality, JSON serialization, every other kind's fixtures) for a shape only one kind currently needs.

Alternative considered: extract a shared `TranslatedListField` helper now. Rejected per the Goals section and the implementation plan's "extract only after two kinds share the exact behaviour" rule — `concepts/concept` is the first kind that needs this.

### D3 — A generic bracket-key grouping convention in `FilesystemManagedSiteInstaller`, not a kind-aware branch

`FilesystemManagedSiteInstaller.frontmatter()` currently renders every `PublicField` as one flat `key: "value"` scalar YAML line, with no knowledge of which kind produced the list. Add one small, kind-agnostic step immediately before that rendering: group consecutive fields whose keys match the `name[index]` / `name[index].subfield` convention into a YAML list block (`name:\n  - value` or `name:\n  - subfield: value\n    subfield: value`), and render every other field exactly as today.

This keeps the installer free of `collection`/`contentType` conditionals — it groups by *key shape*, not by kind identity — and the same mechanism is available to any future kind that adopts the same convention, without another installer change. Fields are still sourced from `approved.ruFields()` / `approved.enFields()` exactly as today; only the rendering step changes.

Alternative considered: give `PublicationKind` a new `projectFrontmatter(List<PublicField>, String locale) -> String` extension point, so each kind fully owns its YAML rendering. Rejected — it would duplicate the identity/`publish`/`translationStatus` scaffolding `frontmatter()` already renders generically for every kind, for a need only the list-grouping step actually has.

Alternative considered: make `structuredData` per-language (`ruStructuredData`/`enStructuredData`) and route `relations`/`examples` through it. Rejected — `structuredData` is deliberately the *invariant* channel (S17c's D3); repurposing it for translated content would blur that distinction for every kind that uses it (`blog/claim`, `bibliography/book`) and would still need the same list-grouping YAML logic this decision already adds more narrowly.

### D4 — `FieldContract` gains one new shape for `relations`; `examples` reuses `nonBlankStringList` as optional

- `examples`: an optional field using the existing `FieldContract.nonBlankStringList("examples")` factory (added in S17c for `authors`), now placed in a kind's *optional* fields for the first time rather than required.
- `relations`: `FieldContract` gains a minimal fixed-shape constructor for "optional list of objects with required non-blank `name` and `relation` string fields" — scoped exactly to this one field, not a general nested-object contract system. `PublicationContractConformanceTest` gains fixtures exercising both fields' presence and absence, alongside `ConceptPublicationKind`'s own admission tests, per the shared-fixture-table discipline already established in S17c.

Alternative considered: model `relations` as an opaque nested-JSON field type. Rejected — it would let the contract accept any shape rather than the one `{name, relation}` shape the site schema actually declares, weakening the exact contract/runtime agreement S17c's conformance harness exists to guarantee.

### D5 — RU-source parsing reuses existing `MarkdownNote` readers; no new parser method

`MarkdownNote.listOfMaps("relations")` (added for `blog/claim`, S17b) and `MarkdownNote.listOfScalars("examples")` (added for `bibliography/book`, S17c) already read the RU frontmatter shapes concept needs. `ConceptPublicationKind` calls both purely to validate structure and obtain the ordered values it flattens into `PublicField` — it does not route either result into `structuredData`, unlike `blog/claim`'s relationship arrays (which stay invariant and untranslated) or `bibliography/book`'s `authors` (also invariant). This is a genuinely different use of the same readers: parsing the RU source shape versus deciding whether the result is translated or invariant is now two separate concerns, and this slice is the first to need the "translated" branch of that choice.

### D6 — Untranslated-but-structurally-valid text is out of this slice's acceptance boundary

Per the prior collaborative-design decision, this slice does not compare English `relations`/`examples`/`notThis` values against their Russian source for equality. The delivered guarantee is: same count, same order, same key/entry structure, non-blank per entry — matching the descoped `public-content-model` `PCM-06` scenario. Whether a translation is *substantively* different Russian-vs-English prose remains a translation-quality concern outside this exporter slice, for every kind, not just `concepts/concept`.

## Risks / Trade-offs

- [Risk] The bracket-key convention is a new implicit contract between `ConceptPublicationKind` (encoder) and `FilesystemManagedSiteInstaller` (decoder); a key-format typo would silently mis-group fields into the wrong YAML shape.
  → Mitigation: define the key-building/parsing logic in one small shared-by-both-ends location (e.g. a package-private helper used by both), and cover it with a focused unit test table for zero, one, and multiple `relations`/`examples` entries, plus a field key that happens to contain literal `[`/`]`/`.` characters if the parser is regex-based (none currently do, but assert it explicitly).
- [Risk] `EnglishCandidateValidator`'s existing key-equality check does not by itself guarantee that entry *content* stayed connected to the right index if a kind ever reorders items outside admission. → Mitigation: keys are index-encoded, so any reordering also changes the key sequence and is already caught by the existing structural check; no new logic needed, but the acceptance test should assert this explicitly for `relations`/`examples`.
- [Risk] Adding `relations` as a new `FieldContract` shape is the second non-scalar contract type after S17c's `STRING_LIST`; a partial implementation could let contract writing and runtime validation diverge. → Mitigation: extend the shared contract/runtime conformance harness in the same slice, per D4.
- [Risk] Descoping the identical-to-source check (D6) means a broken translation worker that echoes Russian text unchanged would still pass validation for `concepts/concept`'s list fields, exactly as it already would for `title`/`description`/`use`/`boundary` on every other kind today. → Mitigation: none needed beyond what already exists; this is an accepted, explicitly documented limitation shared uniformly across all kinds, not a concept-specific regression.

## Migration Plan

1. Add the `relations` shape to `FieldContract` and use `nonBlankStringList` as optional for `examples`; extend `PublicationContractConformanceTest`.
2. Add `ConceptPublicationKind` with admission, the `notThis`/`relations`/`examples` flattening into ordered `PublicField` entries, and register it in `PublicationKinds.installed()`.
3. Add the bracket-key grouping step to `FilesystemManagedSiteInstaller.frontmatter()`, with its own focused unit coverage.
4. Add or update `PrepareHandler`/`EnglishCandidateValidator`-adjacent fixtures proving `concepts/concept` reuses the existing translation and structural-alignment path with no concept-specific exception.
5. Add one full `concepts/concept` acceptance fixture (admit → prepare → approve → build-from-review → install-to-site) covering at least one populated `notThis`, two `relations` entries, and two `examples` entries, then keep the existing suite green.

Rollback is a plain revert. The slice adds a new kind, one new contract field shape, and one small generic rendering step — no irreversible migration or external state change.

## Open Questions

None. The one material ambiguity (translated-vs-invariant posture for `notThis`/`relations`/`examples`) was resolved in the functional collaborative-design pass; the two follow-on technical ambiguities (flattening mechanism ownership, identical-to-source enforcement) were resolved in this pass.
