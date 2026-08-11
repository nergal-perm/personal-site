## Why

`LinkResolver` (`publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolver.java`) already recognizes an embed target as asset-like by extension (`ASSET_EXTENSIONS`) — but it explicitly leaves that syntax untouched: `appendLink` copies `link.group()` verbatim and returns without resolving or blocking. PCM-05 (`openspec/specs/public-content-model/spec.md`) requires the exporter to resolve a referenced publishable asset within the vault (preferring an exact vault-relative match over a unique basename fallback), reject an ambiguous or unsafe target (traversal, symlink escape) with a blocking diagnostic before any candidate replacement, and materialize each accepted asset under a deterministic content-derived name so identical bytes referenced more than once collapse to one public asset. None of this exists today: `VaultReader` (`vault/VaultReader.java`) exposes only `readSource`/`exists`/`listPublishCandidates` for note text — no binary asset read capability — and `CandidateWorkspace` (`candidate/CandidateWorkspace.java`) has no notion of installing an asset alongside RU/EN bodies. Right now, an author's `![[diagram.png]]` embed ships to the candidate and (eventually) the translation worker as raw, unresolved Obsidian syntax that no downstream consumer can render. This is `openspec/implementation-plan.md`'s S14 slice, governed by Haft problem `prob-20260811-ed0f646f` under the slice-sequence decision `dec-20260803-76166a5e`, and is the next pipeline stage after S13's `dec-20260810-cc3f02ed` (`LinkResolver` + `PublicNoteIndex`, sharing `ProtectedRegionScanner`).

`exporter-java`'s `AssetResolver`/`ResolvedAsset`/`AssetValidationException` (`astroexport/assets/`) were read as behavioural evidence only (per this project's standing rule that the legacy implementation is a compatibility oracle, never a code donor) to see what data shape asset resolution needs in practice: SHA-256 content-addressing with a canonicalized suffix (`.jpeg` folds to `.jpg`), exact-vault-relative-path preferred over a basename fallback that must be unique among visible files, rejection of traversal/symlink-escaping/hidden-path targets, and byte-identical dedup keyed by digest with a suffix-family consistency check. That evidence is informative, not binding — this slice's shape (in-memory bytes first, real adapter after) and exact collaborator boundaries are settled in the functional and technical collaborative-design passes, not decided here.

## What Changes

- Add an asset-resolution step to the `prepare` pipeline that runs on `LinkResolver`'s currently-untouched asset-like embed targets (`![[image.png]]`), after link/transclusion resolution, before candidate installation.
- An asset with an exact safe vault-relative match is selected even when another file shares its basename; the accepted asset is materialized under a deterministic content-derived (SHA-256-based) public name, and the embed is rewritten to reference it.
- An asset with no exact match and either zero or more than one basename match, or a target that escapes the vault through traversal or a symlink, blocks preparation with an asset diagnostic — before any candidate is installed or replaced.
- Multiple accepted references to identical asset bytes materialize as exactly one deterministic public asset; every referencing embed points at that same asset.
- Exactly where asset bytes are read from (in-memory fake vs. a real file adapter), how the accepted-asset set is threaded through `PrepareHandler` alongside `LinkResolver`'s output, and how the `CandidateWorkspace` contract grows to carry materialized assets are resolved in the functional and technical collaborative-design passes, not decided here.
- At most one new production boundary adapter — an asset-byte-reading/materializing adapter — proven first against an in-memory fake per the plan's outside-in discipline.

**Explicitly excluded from this slice** (per the S14 boundary in the implementation plan): asset variants, image optimization, remote (non-vault) assets, and media types beyond the existing `ASSET_EXTENSIONS` set (`.png .jpg .jpeg .gif .svg .webp .mp3 .mp4`). Whole-vault discovery (S16) and every content kind beyond the existing essay kind stay out of scope.

## Capabilities

### New Capabilities

None — this slice realizes a requirement (PCM-05) already fully specified in the baseline; it does not introduce a new capability area.

### Modified Capabilities

- `public-content-model`: PCM-05 gains implementation. Its three existing baselined scenarios ("Exact asset path exists", "Basename is ambiguous or unsafe", "Identical bytes are referenced more than once") are the acceptance target; whether any needs sharper scenario text for this slice's boundary (e.g. exactly what "materialize" and "public asset" name/reference resolve to without a real release/site adapter yet) is a question for the functional collaborative-design pass, not decided here.

## Impact

- **Modified:** `publication-exporter/` — a new asset-resolution collaborator (name and shape to be settled in `design.md`) plus its call site in `PrepareHandler`, composing after `LinkResolver.resolve(...)`; `CandidateWorkspace`'s contract grows to carry materialized assets. No CLI surface change, no schema-v2 shape change (the bridge response gains at most a new diagnostic field value for the asset-blocked case, following the existing `Diagnostic.blocking(field, message)` pattern).
- **Untouched:** `exporter-java/` (read-only compatibility oracle), `obsidian-plugin/`, `bridge-contract/schema-v2.json`'s structural shape, `site/`, approved-snapshot and release-materialization code paths, whole-vault discovery (S16), and every other content kind (essay remains the only kind through S17).
- **Governance:** implements Haft problem `prob-20260811-ed0f646f`, under decision `dec-20260803-76166a5e` (slice sequence). Composes directly with `dec-20260810-cc3f02ed` (S13 `LinkResolver`/`PublicNoteIndex` seam) as the next stage in the same prepare-pipeline discipline.
