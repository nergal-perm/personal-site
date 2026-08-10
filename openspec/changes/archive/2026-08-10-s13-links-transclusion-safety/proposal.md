## Why

`PrepareHandler` has no awareness of Obsidian wikilink or embed syntax anywhere in its pipeline — confirmed by exhaustive search: zero matches for `[[`, `wikilink`, `embed`, or `transclu` across `publication-exporter/src/main`. S12's `MarkdownNormalizer` strips Obsidian comments and protects code spans, but treats `[[Some Note]]` and `![[Some Note]]` as ordinary prose text; it neither rewrites nor validates them. PCM-03 (`openspec/specs/public-content-model/spec.md`) requires the exporter to convert unambiguous links to selected public notes into public routes, convert links to private or unresolved notes into safe plain-text labels, and block private transclusions outright — none of which happens today. Right now, an author's `[[Private Draft]]` link or `![[Unpublished Note]]` embed ships to the public site and to the translation worker completely unprocessed, silently leaking vault topology (note titles, internal structure) that the author may not have intended to make public. This is `openspec/implementation-plan.md`'s S13 slice, governed by Haft problem `prob-20260810-cc0935ce` under the slice-sequence decision `dec-20260803-76166a5e`, and composes with S12's decision `dec-20260810-a568f461` (`MarkdownNormalizer` normalize-once-and-reuse) as its next pipeline stage.

`exporter-java`'s `LinkProcessor`/`ManifestLink`/`TransclusionException` were read as behavioural evidence only (per this project's standing rule that the legacy implementation is a compatibility oracle, never a code donor) to see what data shape link resolution actually needs in practice — raw target text, embed-vs-link flag, optional heading fragment, optional alias, and a resolved-identity/resolved-route pair, with a distinct exception path for "transclusion of a non-public note." That evidence is informative, not binding: the legacy resolver builds a whole-vault index (by path, publicId, filename stem, title, aliases) because it operates over an already-discovered manifest; `publication-exporter` has no whole-vault discovery yet (`VaultReader` exposes single-path `readSource`/`exists` plus an unrelated `listPublishCandidates()` scan, with no lookup-by-identity) — S16 is still four slices away. Per the plan's S13 acceptance boundary, this slice keeps resolution in-process, driven by a small caller-supplied set of known notes, rather than adding a real vault-lookup adapter prematurely.

## What Changes

- Add a link/transclusion resolution step to the `prepare` pipeline that runs on the `MarkdownNormalizer`-normalized RU body, recognizing Obsidian wikilink (`[[Target]]`, `[[Target|Alias]]`) and embed (`![[Target]]`) syntax.
- An unambiguous link to a selected public note is rewritten to that note's public route, keeping the authored or resolved display label.
- A link to a private or unresolved note is rewritten to a plain-text label only — no vault path, private route, source identifier, or leftover Obsidian link token in the output.
- An embed (`![[Target]]`) whose target is a private or unresolved note blocks preparation with a transclusion diagnostic, before any candidate is installed or replaced — it is neither inlined nor silently dropped.
- Exactly where the "known notes" set comes from, how ambiguity is defined for this slice's in-process scope, and how the resolution step composes with `MarkdownNormalizer`'s single-pass-and-reuse discipline (including the `sourceFreshness` re-normalization path) are resolved in the functional and technical collaborative-design passes, not decided here.
- Zero new production boundary adapters — this is a pure in-process transform composing with existing `VaultReader`/`intake` data already available to `PrepareHandler`.

**Explicitly excluded from this slice** (per the S13 boundary in the implementation plan): stable semantic occurrence IDs (SEM-02, `ReferenceMap.occurrences()` stays the empty stub it is today) and late-bound target activation (SEM-04/SEM-05) — those are S19/S20. Asset embeds (`![[image.png]]`) are also out of scope — content-addressed asset resolution is S14; this slice only needs to distinguish an asset-like embed target from a note-transclusion target enough not to misclassify it, without resolving or rewriting it.

## Capabilities

### New Capabilities

None — this slice realizes a requirement (PCM-03) already fully specified in the baseline; it does not introduce a new capability area.

### Modified Capabilities

- `public-content-model`: PCM-03 gains implementation. Its three existing baselined scenarios ("Public target is unambiguous", "Private target is linked", "Private target is transcluded") are the acceptance target; whether any needs sharper scenario text for this slice's in-process resolution scope (e.g. what "unambiguous" and "unresolved" mean without whole-vault discovery) is a question for the functional collaborative-design pass, not decided here.

## Impact

- **Modified:** `publication-exporter/` — a new link/transclusion-resolution collaborator (name and shape to be settled in `design.md`) plus its call site in `PrepareHandler`, composing after `MarkdownNormalizer.normalize(...)`. No new port/adapter, no CLI surface change, no schema-v2 change (the bridge response gains at most a new diagnostic field value for the transclusion-blocked case, following the existing `Diagnostic.blocking(field, message)` pattern — not a new response shape).
- **Untouched:** `exporter-java/` (read-only compatibility oracle), `obsidian-plugin/`, `bridge-contract/schema-v2.json`'s structural shape, `site/`, approved-snapshot and release-materialization code paths, asset resolution (S14), whole-vault discovery (S16), and every other content kind (essay remains the only kind through S17).
- **Governance:** implements Haft problem `prob-20260810-cc0935ce`, under decision `dec-20260803-76166a5e` (slice sequence). Composes directly with `dec-20260810-a568f461` (S12 `MarkdownNormalizer` seam) as the next stage in the same normalize-once-and-reuse pipeline discipline.
