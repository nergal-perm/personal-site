## MODIFIED Requirements

### Requirement: ADM-04 Enforce kind-specific source contracts

The exporter SHALL validate the required metadata and structured body sections for the selected publication kind before content preparation or release.

#### Scenario: Kind-specific contract is complete
- **GIVEN** a selected book with author metadata, an album with artist/work metadata and required body sections, a concept with description and definition, an editorial page with an allowed page key and valid structured body, or an essay with title and description
- **WHEN** the note is validated
- **THEN** the kind-specific contract passes

#### Scenario: Kind-specific contract is incomplete
- **GIVEN** a selected note missing a field or body section required by its publication kind
- **WHEN** the note is validated
- **THEN** processing is blocked before translation or release
- **AND** the diagnostic names the kind and missing requirement

## Why this is a real delta, not a scope pin

ADM-04's existing "Kind-specific contract is complete" scenario illustrates required fields for book, album, concept, and editorial-page kinds — essay is conspicuously absent from that list, and no other requirement in the baseline names "title" or "description" anywhere (confirmed by search). This is the gap S07 is the first slice to need closed: `site/src/content.config.ts`'s Astro schema requires non-empty `title`/`description` on every essay entry, and these are vault-author-provided fields (the Obsidian plugin gates on their presence before allowing "Prepare to publication"), not exporter-synthesized ones. "Kind-specific contract is incomplete" already covers the missing-field failure mode generically — no change needed there, since "a field ... required by its publication kind" already describes a missing title or description exactly. Only the illustrative GIVEN clause of the passing scenario needed essay added to it.

## Not touched by this change

ADM-01, ADM-02, ADM-03, ADM-05, and ADM-06 are unaffected — this slice adds two more fields to essay's already-established kind-specific contract (ADM-04's existing mechanism), it does not change how notes are selected, confined to the vault, identified, scoped, or contract-exported.
