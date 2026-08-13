## Why

`editorial/curated_page` is the last kind in the S17a-S17f content-kind ladder, and the only one whose managed artifact is not a Markdown content-collection entry: the live site already reads generated static JSON at `site/src/data/pages/{ru,en}/*.json` (e.g. `about.json`) for its curated pages (home, essays, claims, notes, music, library, concepts, now, about), rendered by dedicated views and routed outside `src/content`. The current exporter has no `PublicationKind` for it, so the legacy `exporter-java` `EditorialParser` remains the only tool able to produce that JSON, and the plan's own G7 gate ("freeze current editorial grammar as one edition or version it independently") is still undecided. Both gaps block admitting, preparing, approving, or releasing any curated page through the governed pipeline.

## What Changes

- Record the G7 decision explicitly: freeze the current nine-page-type editorial grammar (`home`, `essays`, `claims`, `notes`, `music`, `library`, `concepts`, `now`, `about`, keyed by frontmatter `editorialPage`) as one contract edition for this slice, rather than versioning it. Full parity across all nine page types is out of scope for this slice; it is not scheduled as a further content-kind slice in the plan.
- Add a `CuratedPagePublicationKind` for `editorial/curated_page`, admitting exactly one representative page type end-to-end (`about`: a fixed-shape grammar of a lead paragraph, a non-empty list of principle heading/prose pairs, and a colophon paragraph — the simplest of the nine legacy grammars) through the existing `PublicationKind` seam.
- Extend the release path with a kind-owned artifact projection: `editorial/curated_page` produces one JSON page-data document per locale instead of Markdown+frontmatter, since its target is `site/src/data/pages/{locale}/{id}.json`, not `site/src/content/{collection}/{locale}/{id}.md`. This is the first kind to exercise the plan's "extract a generic safe-artifact projection only when a kind actually needs a different managed artifact shape" release rule.
- Extend the managed-site installer to write the JSON payload under the already-anticipated `src/data/pages` payload root (present in `FilesystemManagedSiteInstaller.PAYLOAD_ROOTS` since an earlier slice but currently unused) instead of `src/content`.
- Add one end-to-end `editorial/curated_page` (`about`) acceptance fixture and update contract/runtime conformance so `write-publication-contract` includes the new kind.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `publication-admission`: ADM-03 gains a distinct `editorial/curated_page` admission path (page type `about` for this slice); ADM-04 gains the curated-page source contract (`editorialPage`/`publicSearchable` fields plus the `about` body grammar: lead, principles, colophon); ADM-06 gains deterministic `editorial/curated_page` contract output.
- `public-content-model`: PCM-02 gains curated-page-specific JSON-artifact projection and route ownership outside `src/content` (the release-materialization requirements are already kind-neutral — "publishable RU and EN pages", "managed trees and files" — and the managed-site installer's payload roots already reserve `src/data/pages`, so no `release-materialization` delta is needed); PCM-06 gains the structural-alignment rules for which `about`-page fields are translated prose versus invariant.

## Impact

- `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/`: new `CuratedPagePublicationKind` plus any admission/parser/contract additions the `about` grammar and G7 decision require.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/`, `site/`: a kind-owned release/install projection point for JSON page artifacts, reusing `FilesystemManagedSiteInstaller`'s already-declared `src/data/pages` payload root.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/`, `reference/`: whatever `CandidateSnapshot`/`PublicField` carrier changes the JSON projection needs, reusing the existing translated-field carrier rather than inventing a parallel one unless the `about` fixture proves it insufficient.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/`: `PublicationKinds.installed()` registration and any `FieldContract` shape additions.
- `publication-exporter/src/test/`: new `editorial/curated_page` (`about`) acceptance coverage plus conformance and unit tests for admission and JSON projection.
- `.haft/decisions/`: new decision recording G7.
