## Why

`music/album` is already a real public site type: the live Astro site loads music review entries, publishes them under `/music/`, and renders album-specific metadata in `AlbumPage.astro`. The current exporter still treats that collection/content-type pair as unsupported, so there is no way to admit, prepare, approve, release, or contract an album note through the governed publication pipeline, even though one real album fixture already ships on the live site.

## What Changes

- Add a `AlbumPublicationKind` for `music/album`, including `/music/` route ownership and deterministic contract output through the existing `PublicationKind` seam. `music/track` (the schema's other `reviewType` value) is explicitly out of scope for this slice.
- Admit album notes with the existing required `title`/`description` identity fields, plus the album schema's own required fields (`artist`, `work`, `context`, `association`).
- Decide, against the live fixture pair's real evidence, the translated-vs-invariant posture for every album field: `context`, `association`, `care`, and likely `format` are translated prose (clearly divergent RU/EN in the shipped fixture); `artist` and `work` are invariant (byte-identical RU/EN — proper nouns); `listenFor` is a translated list of strings; `genreTags` is a list that does not read as translated content; `releaseDate`, `streamingUrl`, and `bandcampEmbedUrl` are invariant.
- Resolve, through collaborative design, whether S17d's translated-list flattening mechanism (built for `concepts/concept`'s `relations`/`examples`, deliberately kept private to that kind) should now be extracted into a shared mechanism, since `music/album`'s `listenFor` is the second real kind that needs a translated list — this is exactly the extraction trigger the implementation plan's S17c-S17e rule anticipates, and must be decided explicitly rather than silently repeated or silently generalized.
- Add an invariant list-of-scalars carrier for `genreTags`, reusing the pattern already proven for `bibliography/book`'s `authors` (S17c).
- Add one end-to-end `music/album` acceptance fixture and update contract/runtime conformance so `write-publication-contract` includes the new kind.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `publication-admission`: ADM-03 gains a distinct `music/album` admission path; ADM-04 gains the album source contract, including its required and optional fields; ADM-06 gains deterministic `music/album` contract output.
- `public-content-model`: PCM-02 gains album-specific route and metadata projection for `/music/`; PCM-06 gains the structural-alignment rules for whichever album fields are carried through translation versus kept invariant.

## Impact

- `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/`: new `AlbumPublicationKind` plus any minimal admission/parser/contract changes the decided field posture requires.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/`, `translation/`, `prepare/`, and `site/`: album-specific projection, and — depending on the collaborative-design extraction decision — either continued reuse of `ConceptPublicationKind`'s private flattening pattern or a newly shared translated-list mechanism.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/`: `FieldContract`/contract serialization updates if a new field shape is required, and `PublicationKinds.installed()` registration.
- `publication-exporter/src/test/`: new `music/album` acceptance coverage plus conformance and unit tests for admission and projection.
