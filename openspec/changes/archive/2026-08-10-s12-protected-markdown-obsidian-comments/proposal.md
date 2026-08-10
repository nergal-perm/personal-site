## Why

`PrepareHandler` installs the Russian candidate body as the frontmatter-stripped source text, verbatim — no Markdown normalization step exists anywhere in `publication-exporter`. PCM-04 (`openspec/specs/public-content-model/spec.md`) requires that Obsidian-only comment syntax (`%%...%%`) be removed from publishable prose, and that code, inline code, and other declared protected regions be left semantically unchanged — including any link-like, wiki-link, or embed-like text that happens to sit inside those protected regions, which must survive byte-for-byte. Today, any `%%...%%` editorial aside an author leaves in a note ships straight to the public site and to the translation worker verbatim; any link-like text an author puts in a code sample for illustration (e.g. `` `[[Some Note]]` `` as a syntax example) is equally exposed to whatever S13 does with real link syntax once it lands, unless protected regions are established as inviolable first. PCM-04 is unimplemented in the new exporter — S01–S11 never touched Markdown content, only frontmatter, identity, and workflow state. This is `openspec/implementation-plan.md`'s S12 slice, governed by Haft problem `prob-20260810-d063b24b` under the slice-sequence decision `dec-20260803-76166a5e`.

`exporter-java`'s `MarkdownScanner`/`MarkdownNormalizationTest` were read as behavioural evidence only (per this project's standing rule that the legacy implementation is a compatibility oracle, never a code donor) to understand what "protected region" actually has to mean in practice — fenced code with same-or-longer closing-fence matching, inline code spans, raw HTML `<pre>` blocks, and CRLF line endings all turned out to be real edge cases the legacy implementation had to solve, not hypothetical ones. `dec-20260804-9f43c17f` (S03's RU-normalization-depth gate, G4) already decided semantic/site-acceptance normalization over byte-for-byte legacy compatibility for the *plain* case (no comments, no protected regions) — this slice extends that normalization only for the new PCM-04 case, without reopening or narrowing the plain-essay pass-through guarantee S03 already established.

## What Changes

- Add a Markdown-normalization step to the `prepare` pipeline that strips Obsidian comment spans (`%%...%%`) from Russian source text, except where they occur inside a declared protected region (fenced code, inline code — exact protected-region kind list to be pinned in `design.md`'s technical collaborative-design pass, informed by but not copied from `exporter-java`'s evidence).
- Guarantee that any link-like, wiki-link, or embed-like text inside a protected region survives normalization byte-for-byte, regardless of what it would otherwise match as Obsidian-comment or (later, S13) link syntax.
- Zero new production boundary adapters — this is a pure in-process text transform with no I/O, config, or external dependency.
- Resolve, as part of the technical design pass, exactly where in `PrepareHandler`'s existing control flow the same normalized RU text must be used consistently: the `matchingApprovedBaseline` unchanged-check (`RussianDiff.between`), the `TranslationJob` built for the worker, and the installed candidate body — normalizing in only one place and reusing the result, rather than three independent call sites each normalizing (or forgetting to normalize) on their own.

**Explicitly excluded from this slice** (per the S12 boundary in the implementation plan): resolving actual links, transclusions, and assets (S13/S14's scope) — this slice only guarantees that link-*like text* inside protected regions is not corrupted; it does not interpret, validate, or rewrite any link, transclusion, or asset reference, protected or not.

## Capabilities

### New Capabilities

None — this slice realizes a requirement (PCM-04) already fully specified in the baseline; it does not introduce a new capability area.

### Modified Capabilities

- `public-content-model`: PCM-04 gains implementation. Its two existing baselined scenarios ("Link-like text appears in protected content", "Obsidian comment appears in publishable prose") are realized as-is by this slice; whether either needs sharper scenario text (e.g. naming the specific protected-region kinds in scope) is a question for the functional collaborative-design pass, not decided here.

## Impact

- **Modified:** `publication-exporter/` — a new Markdown-normalization collaborator (name and shape to be settled in `design.md`) plus its call site(s) in `PrepareHandler`. No new port/adapter, no CLI surface change, no schema-v2 change (the bridge response shape is unaffected — this only changes the bytes installed as the candidate body).
- **Untouched:** `exporter-java/` (read-only compatibility oracle), `obsidian-plugin/`, `bridge-contract/schema-v2.json`, `site/`, approved-snapshot and release-materialization code paths, and every other content kind (essay remains the only kind through S17).
- **Governance:** implements Haft problem `prob-20260810-d063b24b`, under decision `dec-20260803-76166a5e` (slice sequence). Fires a refresh trigger already named on `dec-20260804-9f43c17f` ("S12–S14 land and this decision's scope boundary needs re-examination") — to be verified against, not silently superseded, once this slice lands.
