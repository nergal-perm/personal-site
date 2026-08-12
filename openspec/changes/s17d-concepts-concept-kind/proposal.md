## Why

`concepts/concept` is already a real public site type: the live Astro site loads concept entries, publishes them under `/concepts/`, and renders concept-specific structure (`notThis` boundary callout, `relations`, `examples`) in `ConceptPage.astro`. The current exporter still treats that collection/content-type pair as unsupported, so there is no way to admit, prepare, approve, release, or contract a concept note through the governed publication pipeline, even though two concept fixtures already ship on the live site.

## What Changes

- Add a `ConceptPublicationKind` for `concepts/concept`, including `/concepts/` route ownership and deterministic contract output through the existing `PublicationKind` seam.
- Admit concept notes with the existing required `title`/`description` fields, following the same shape as `blog/essay`, `blog/note`, `blog/claim`, and `bibliography/book`.
- Resolve, through collaborative design, how `notThis` (optional prose), `relations` (`{name, relation}[]`), and `examples` (`string[]`) are projected. Unlike `bibliography/book`'s invariant `authors` (S17c) or `blog/claim`'s invariant relationship lists with link targets (S17b), these three concept fields are rendered as translated RU→EN prose on the live site, and the current pipeline has no carrier for a translated scalar list or translated structured list — only the single-valued `PublicField` list is translated today. This slice either extends the translation carrier just enough to cover these fields, or explicitly blocks the ones that cannot be safely translated in this slice, mirroring S17c's precedent of hard-blocking `bibliography/book`'s `selectedQuote` rather than silently mistranslating or copying Russian prose into English candidates.
- Add one end-to-end `concepts/concept` acceptance fixture and update contract/runtime conformance so `write-publication-contract` includes the new kind.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `publication-admission`: ADM-03 gains a distinct `concepts/concept` admission path; ADM-04 gains the concept source contract, including the decided posture for `notThis`/`relations`/`examples`; ADM-06 gains deterministic `concepts/concept` contract output.
- `public-content-model`: PCM-02 gains concept-specific route and metadata projection for `/concepts/`; PCM-06 gains the structural-alignment rules for whichever concept fields are carried through translation versus blocked in this slice.

## Impact

- `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/`: new `ConceptPublicationKind` plus any minimal admission/parser/contract changes the decided field posture requires.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/`, `translation/`, `prepare/`, and `site/`: concept-specific projection, and — if collaborative design selects it — a bounded extension of the translated-field carrier for list-shaped prose.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/`: `FieldContract`/contract serialization updates if a new field shape is required, and `PublicationKinds.installed()` registration.
- `publication-exporter/src/test/`: new `concepts/concept` acceptance coverage plus conformance and unit tests for admission, projection, and any blocked-field diagnostics.
