## Why

`bibliography/book` is already a real public site type: the live Astro site loads bibliography entries, publishes them under `/library/`, and renders book-specific metadata in `BookPage.astro`. The current exporter still treats that collection/content-type pair as unsupported, so there is no way to admit, prepare, approve, release, or contract a book note through the governed publication pipeline.

## What Changes

- Add a `BookPublicationKind` for `bibliography/book`, including `/library/` route ownership and deterministic contract output through the existing `PublicationKind` seam.
- Admit a required non-empty author list for books and project invariant book metadata (`publication`, `publicationDate`, `start`, `end`, `readingStatus`) into managed site frontmatter.
- Extend the current scalar-first admission/contract path just enough to support a required string-list source field for `authors`, without introducing a generic schema framework.
- Carry book-only translatable scalar metadata (`use`, `boundary`) through the existing translated `PublicField` path so RU and EN candidates stay structurally aligned.
- Block `selectedQuote` for `bibliography/book` in this slice instead of silently dropping it or copying Russian-only structured text into English, because the current pipeline has no mixed translated-structured carrier yet.
- Add one end-to-end `bibliography/book` acceptance fixture and update contract/runtime conformance so `write-publication-contract` includes the new kind.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `publication-admission`: ADM-03 gains a distinct `bibliography/book` admission path; ADM-04 gains the book source contract, including required authors and the temporary `selectedQuote` block; ADM-06 gains deterministic `bibliography/book` contract output and the first required non-scalar field description.
- `public-content-model`: PCM-02 gains book-specific route and metadata projection for `/library/`; PCM-06 gains the structural-alignment rules for book translatable scalar metadata versus invariant book metadata.

## Impact

- `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/`: new `BookPublicationKind` plus the minimal admission/parser/contract changes needed for required author lists and blocked `selectedQuote`.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/`, `translation/`, and `site/`: book-specific projection and translation alignment for `use` and `boundary`, and invariant frontmatter emission for author/publication metadata.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/`: `FieldContract`/contract serialization updates for the new required list-shaped field and `PublicationKinds.installed()` registration.
- `publication-exporter/src/test/`: new `bibliography/book` acceptance coverage plus conformance and unit tests for admission, projection, and the `selectedQuote` block.
