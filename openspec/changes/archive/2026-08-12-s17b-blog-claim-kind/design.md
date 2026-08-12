## Context

Every carrier between admission and site installation is essay-shaped — a fixed `(body, title, description)` triple, not a generalized "kind-declared public fields" model:

- `CandidateSnapshot` (candidate package): `(ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription, referenceMap)`.
- `TranslationWorker.translate(TranslationJob, String ruBody, String ruTitle, String ruDescription) -> TranslationOutcome` wraps `EnglishTranslation(body, title, description)`.
- `TranslationJob.forSource(ruBody, ruTitle, ruDescription)` fingerprints exactly those three strings.
- `RussianDiff.between(approvedBody, approvedTitle, approvedDescription, currentBody, currentTitle, currentDescription)` and `EnglishCandidateValidator.validate(ruBody, enBody, enTitle, enDescription)` are both hardcoded to the same triple.
- `ReferenceMap` persists six named hashes: `ruHash`/`enHash` (body) plus `ruTitleHash`/`enTitleHash`/`ruDescriptionHash`/`enDescriptionHash`.
- `FilesystemCandidateWorkspace`/`FilesystemApprovedSnapshotWorkspace` persist one flat file per field: `ru.md`, `en.md`, `ru.title`, `en.title`, `ru.description`, `en.description`, `references.json`.
- `FilesystemManagedSiteInstaller.frontmatter()` writes a fixed key set (`id`/`title`/`description`/`publish`/`contentType`/`language`/`sourceLanguage`/`sourceHash`/`translationStatus`/`translationOf`) — no kind-specific fields exist today because none was ever needed.
- `ReleaseOutputStore`/`ReleaseProvenance`/`FilesystemReleaseOutputStore` only ever carry `ruBody`/`enBody` plus hash provenance — title/description never reach them. `FilesystemManagedSiteInstaller.install()` reads title/description straight from the `CandidateSnapshot` returned by `ApprovedSnapshotWorkspace.read()`, bypassing `ReleaseOutputStore` entirely. **This means the release package needs no change for S17b.**

`site/src/content.config.ts`'s `blogClaim` requires a non-optional `statement` (translated prose, same status as title/description) plus five relationship arrays and `sources` (untranslated, per the collaborative-design decision recorded below). The collaborative-design pass concluded this is exactly the evidence `openspec/implementation-plan.md`'s "Kind-neutral lifecycle rule" anticipated: replace the fixed `body`/`title`/`description` parameter trains with an immutable, ordered "public fields" carrier — generalizing once, now, so `S17c`–`S17f` reuse the same seam instead of repeating this refactor.

No real production data exists under any of these file formats yet (confirmed while resolving `prob-20260811-0b8b9f2d`), so this migration needs no backward-compatibility story.

## Goals / Non-Goals

**Goals:**
- `blog/claim` completes admit → prepare → approve → release → site install through the same handlers already proven for `blog/essay`/`blog/note`.
- Every carrier between admission and site frontmatter generalizes from a fixed `(body, title, description)` triple to `(body, List<PublicField>)`, where `PublicField` is an ordered `(key, value)` pair. Essay/note declare exactly `[title, description]`; claim declares `[title, description, statement]`. No kind-specific conditionals appear in the generic pipeline classes (`TranslationJob`, `RussianDiff`, `EnglishCandidateValidator`, `CandidateSnapshot`, `TranslationWorker`).
- `blog/claim`'s relationship arrays (`supports`/`opposes`/`assumes`/`refines`/`contradicts`) and `sources` are validated against their site transport shape, then carried as one opaque, untranslated, kind-owned frontmatter fragment (produced once by `ClaimPublicationKind` at admission time), not machine-translated and not modeled as generic `PublicField`s.
- Approved-baseline matching and the post-translation freshness recheck compare `structuredData` byte for byte in addition to body/public fields, so a metadata-only edit cannot reuse an approval or install a stale fragment.
- `ReferenceMap`/`references.json` generalizes its per-field hashes (`ruTitleHash`/`enTitleHash`/`ruDescriptionHash`/`enDescriptionHash`) into two whole-document hashes (`ruFieldsHash`/`enFieldsHash`) plus one `structuredDataHash`, replacing the fixed four-field shape.
- `FilesystemCandidateWorkspace`/`FilesystemApprovedSnapshotWorkspace` persist the ordered field list as one JSON document per locale (`ru.fields.json`/`en.fields.json`) instead of one flat file per field.
- `FilesystemManagedSiteInstaller.frontmatter()` writes every declared `PublicField` (title/description always first, in order) plus, when present, the kind-owned structured-data fragment appended verbatim before the closing `---`.
- The cross-kind collision guard (`dec-20260811-02b96a37`) continues to protect a three-kind `blog` collection with no changes to its own logic (it reads `references.json`'s `identity`, which is untouched by this refactor).

**Non-Goals:**
- No machine translation of relationship `label` text or any other structured-data content in this slice — RU and EN candidates carry byte-identical structured data, mirroring how `blog/essay`'s own `sources: string[]` field is already left unpopulated today. Revisit only when a future kind's evidence demands it.
- No resolution or validation of relationship `target` values against known publications — they remain opaque strings, per the collaborative-design decision on `PCM-02`. Semantic reference resolution stays reserved for `openspec/implementation-plan.md`'s `SEM-01`/`SEM-02` slices (S18–S20).
- No change to `ReleaseOutputStore`/`ReleaseProvenance`/`FilesystemReleaseOutputStore` — confirmed unreachable by this refactor (see Context).
- No change to `book`/`album`/`concept`/`curated_page` — out of scope until their own slices.
- No backward-compatibility/migration story for the file-format changes — no real data exists under the old format yet.

## Decisions

### D1 — `PublicField`: a small immutable ordered `(key, value)` pair

```java
public final class PublicField {
    private final String key;
    private final String value;
    public static PublicField of(String key, String value) { ... }
    public String key() { ... }
    public String value() { ... }
}
```

Every kind's `admit()` produces an ordered `List<PublicField>` with `title` always first, `description` always second (preserving every existing accessor's effective meaning), followed by any kind-specific translatable fields. `EssayPublicationKind`/`NotePublicationKind` produce exactly `[title, description]` — behaviour-preserving. `ClaimPublicationKind` produces `[title, description, statement]`.

**Alternative considered:** a `Map<String,String>`. Rejected — order matters for deterministic fingerprinting (`TranslationJob`), diffing (`RussianDiff`), and frontmatter emission; a `List<PublicField>` makes the ordering explicit and typed rather than relying on `LinkedHashMap` iteration-order discipline.

### D2 — Generalize `TranslationJob`, `TranslationWorker`, `RussianDiff`, `EnglishCandidateValidator`, `EnglishTranslation` to `(body, List<PublicField>)`

- `TranslationJob.forSource(String ruBody, List<PublicField> ruFields)` — fingerprint iterates fields in order (`key.length() + ":" + key + value.length() + ":" + value` per field, same canonicalization style as today, generalized from two named parameters to a loop).
- `TranslationWorker.translate(TranslationJob job, String ruBody, List<PublicField> ruFields) -> TranslationOutcome`; `EnglishTranslation(String body, List<PublicField> fields)` — the returned list has the same keys, in the same order, as the request.
- `RussianDiff.between(String approvedBody, List<PublicField> approvedFields, String currentBody, List<PublicField> currentFields)` — the existing `labeledFieldDiff("title", ...)`/`labeledFieldDiff("description", ...)` calls become one loop over the (already correctly ordered) field list, labeling each diff line with that field's own `key()`.
- `EnglishCandidateValidator.validate(String ruBody, String enBody, List<PublicField> enFields)` — the blank-field and internal-`/ru/`-route checks iterate all fields instead of naming `enTitle`/`enDescription`; the dropped-URL/dropped-asset-reference checks stay body-only (unchanged — they already don't look at title/description).

**Alternative considered:** keep these five classes fixed at `(body, title, description)` and give `ClaimPublicationKind` its own parallel `statement`-aware validator/differ/fingerprinter, invoked by kind-specific branches in `PrepareHandler`. Rejected — this is exactly the "no new collection/type conditionals appear in generic orchestration" the plan warns against, and duplicates fingerprint/diff/validation logic that must stay behaviourally identical to avoid two independently-maintained sources of truth for what "the same content" means.

### D3 — `CandidateSnapshot` carries `(ruBody, enBody, List<PublicField> ruFields, List<PublicField> enFields, String structuredData, ReferenceMap)`

`ruTitle()`/`enTitle()`/`ruDescription()`/`enDescription()` accessors are removed; callers needing title/description look them up by key (`fields.stream().filter(f -> f.key().equals("title"))...`, wrapped in a small `fieldValue(List<PublicField>, String key)` helper used by `PrepareHandler`, `FilesystemManagedSiteInstaller`, and tests). `structuredData` is a single opaque string — empty for essay/note, a pre-rendered YAML fragment for claim (see D5) — identical on both the RU and EN sides of the snapshot, since it is never translated.

### D4 — `ReferenceMap` replaces four named field-hashes with two whole-document hashes, plus one structured-data hash

`ruTitleHash`/`enTitleHash`/`ruDescriptionHash`/`enDescriptionHash` are replaced by `ruFieldsHash`/`enFieldsHash` (SHA-256 of the canonical `ru.fields.json`/`en.fields.json` document bytes) and one new `structuredDataHash` (SHA-256 of `structuredData`, empty-string hash for essay/note). `ruHash`/`enHash` (body) are unchanged. `FilesystemApprovedSnapshotWorkspace.validateSnapshot()` and `FilesystemCandidateWorkspace`'s equivalent read-path checks compare these against the actual persisted file bytes, exactly as today, just against two documents instead of four.

**Alternative considered:** keep four named hashes and add two more (`ruStatementHash`/`enStatementHash`) as a fifth/sixth pair, generalizing later only if a future kind needs a seventh. Rejected — this is the same "keep adding named fields" pattern D1/D2 already rejected, and would require yet another pair for every future kind's own translatable fields.

### D5 — Kind-owned structured-data fragment, appended verbatim by the site installer

`ClaimPublicationKind` (not `FilesystemManagedSiteInstaller`) validates relationship and source metadata against `site/src/content.config.ts`'s declared list/object/scalar transport shape, rejecting malformed values and undeclared fields without resolving targets or translating nested content. It then renders the relationship arrays and preserves `sources` as one pre-formatted YAML fragment string (e.g. `"supports:\n  - label: \"...\"\n    target: \"...\"\n"`) at admission time, using the same `doubleQuotedYamlScalar`-style escaping `FilesystemManagedSiteInstaller` already uses for scalar values (extracted into a small shared `YamlScalar` helper so both call sites use identical escaping). This fragment rides unchanged through `AdmittedPublication` → `CandidateSnapshot.structuredData` → `ApprovedSnapshotWorkspace` → `FilesystemManagedSiteInstaller.frontmatter()`, which appends it verbatim (if non-empty) after the generic `PublicField` lines and before the closing `---`. `FilesystemManagedSiteInstaller` never parses or interprets it.

**Alternative considered:** model relationship entries as structured `PublicField`-like objects (e.g. `PublicField.structured(key, List<Map<String,String>>)`) and have the shared site installer render them generically. Rejected — genuinely different kinds (claim's label/target pairs today; book's author list or album's tracklist tomorrow) would each need their own YAML shape, and teaching the *shared* installer every kind's shape is exactly the "generic schema-framework" and "cross-kind conditionals in generic orchestration" the plan excludes. A kind rendering its own fragment keeps that variability inside the kind, matching `PublicationKind`'s existing role as owner of its own contract and route policy (S17a's D6).

### D6 — File-format migration: `ru.fields.json`/`en.fields.json` replace `ru.title`/`en.title`/`ru.description`/`en.description`

`FilesystemCandidateWorkspace`/`FilesystemApprovedSnapshotWorkspace` write one JSON document per locale — `[{"key":"title","value":"..."},{"key":"description","value":"..."}]` for essay/note, with a third `statement` entry for claim — instead of two flat files per locale. `containsCandidateSnapshot()`'s required-file list, `writeSnapshot()`, and `snapshotFrom()` update accordingly. The opaque fragment is persisted separately as `structured.json`; `references.json` carries the existing identity/hash fields plus `structuredDataHash`, not the fragment itself. `ru.md`/`en.md` are unchanged.

**Alternative considered:** keep the flat-file-per-field format and add `ru.statement`/`en.statement` as two more files for claim, generalizing the file set only when a kind needs yet another field. Rejected for the same reason as D4 — every future kind's own fields would repeat this exercise, and a flat-file-per-field format cannot express an *unbounded* ordered field list without inventing a naming convention for field N anyway. One JSON document per locale is the natural generalization once the in-memory carrier (D1–D3) is already a `List<PublicField>`.

## Risks / Trade-offs

- **[Risk]** This refactor touches significantly more surface than S17a's (`TranslationJob`, `TranslationWorker` + `NullTranslationWorker`, `RussianDiff`, `EnglishCandidateValidator`, `EnglishTranslation`, `TranslationOutcome`, `CandidateSnapshot`, `ReferenceMap` + `ReferenceMapCodec`, both real filesystem workspaces, `FilesystemManagedSiteInstaller`, `PrepareHandler`, plus every fixture/test referencing any of these types) → **Mitigation**: the existing `blog/essay` and `blog/note` acceptance suites are the safety net — every one of D1–D6 is required to be behaviour-preserving for `title`/`description` specifically (fields `[title, description]` in that order, hash coverage unchanged in spirit, file *documents* changed but content round-trips identically). `tasks.md` sequences the refactor as its own behaviour-preserving commit (essay/note-only, suite green throughout) before the new-behaviour commit that admits `blog/claim`, mirroring S17a's two-commit shape (D6/Migration Plan there).
- **[Risk]** Changing `references.json`'s hash-field shape and the candidate/approved file set is a real wire-format change → **Mitigation**: confirmed no real production data exists under the old format (see Context); no migration code is in scope.
- **[Risk]** A kind-owned YAML fragment (D5) could drift from the shared scalar-escaping helper if not actually shared → **Mitigation**: extract `doubleQuotedYamlScalar` into one small shared `YamlScalar` utility used by both `FilesystemManagedSiteInstaller` and `ClaimPublicationKind`, with a unit test proving both call sites escape identically.
- **[Risk]** Leaving relationship `label` untranslated is a real, user-visible limitation (English readers see Russian-authored labels) → **Mitigation**: explicitly documented as a Non-Goal and in `spec.md`'s `PCM-06` scenario; revisit once a kind's evidence demands translated structured content, not before.

## Migration Plan

1. Add `PublicField`; generalize `TranslationJob`, `EnglishTranslation`, `TranslationOutcome`, `TranslationWorker` (+ `NullTranslationWorker`), `RussianDiff`, `EnglishCandidateValidator` to `(body, List<PublicField>)`, behaviour-preserving for the existing `[title, description]` shape. `PrepareHandler` updates its call sites to build/pass `List.of(PublicField.of("title", ruTitle), PublicField.of("description", ruDescription))` from `AdmittedPublication`. Essay/note suites stay green throughout.
2. Generalize `CandidateSnapshot` (D3), `ReferenceMap`/`ReferenceMapCodec` (D4), and both real filesystem workspaces' file formats (D6). `FilesystemManagedSiteInstaller.frontmatter()` iterates `PublicField`s instead of naming `title`/`description`, and appends `structuredData` verbatim when non-empty (empty for essay/note — no observable output change). Essay/note suites stay green throughout; this is the last essay/note-only, behaviour-preserving commit.
3. Add `ClaimPublicationKind`: admits identity + non-blank `statement`, builds `[title, description, statement]` `PublicField`s, and renders its relationship arrays/`sources` into the `structuredData` YAML fragment (D5) using the shared `YamlScalar` helper. Register it in `PublicationKinds.installed()`.
4. Add the `blog/claim` acceptance fixture (admit → prepare → approve → release → site install, asserting `statement` and at least one populated relationship array survive to the installed site file) and the contract-conformance fixture row for `ADM-06`.

Steps 1–2 are one behaviour-preserving refactor (essay/note-only, suite green throughout, no new observable behaviour); steps 3–4 are the new-behaviour commits that actually admit `blog/claim`. Rollback at any commit boundary is a plain revert — no persisted production data or migration involved (confirmed in Context).

## Open Questions

None — `blog/claim`'s field shape (identity + title + description + statement, optional relationship arrays and `sources`) is fully determined by `site/src/content.config.ts`'s existing `blogClaim` schema, and the whole-publication-value carrier direction, structured-data translation posture, and `target`-resolution posture were each resolved during this slice's collaborative-design pass (recorded above and in `spec.md`).
