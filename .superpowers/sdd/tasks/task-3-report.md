# Task 3 Report — `AboutPageBody` and `CuratedPagePublicationKind`

## Goal

Implement the Task 3 `editorial/curated_page` admission slice for the `about` page, using the exact grammar and code shape from `task-3-brief.md`, while preserving the inherited Markdown managed-artifact projection for the later Task 5 override.

## What was built

- Added immutable, purpose-built `AboutPageBody` parsing for the required `## Кратко`, `## Eyebrow`, `## Лид`, `## Принципы`/`###` principles, and `## Колофон` sections.
- Added `CuratedPagePublicationKind` for `editorial/curated_page`:
  - admits only `editorialPage: about`;
  - requires `publicId` to equal `editorialPage`;
  - validates `id`, `title`, lowercase-slug `publicId`, and the about body;
  - translates the declared fields in order, including bracket-indexed principles;
  - emits fixed JSON `structuredData` with `searchable` and `type`;
  - leaves `projectManagedArtifact` inherited from `PublicationKind` as required for Task 3.
- Added the shared curated-page fixture type/table and registered the kind in `PublicationKinds.installed()`.
- Updated the installed-kind contract CLI expectation from six to seven kinds so the existing suite reflects the required registration. No Task 4/5 contract-detail or JSON-artifact implementation was added.
- Marked OpenSpec Task 3 items 3.1–3.6 complete.

## Test counts

- Before changes: `mvn -f publication-exporter/pom.xml test` — 765 passed, 0 failures, 0 errors.
- Focused Task 3 verification: `mvn -f publication-exporter/pom.xml test -Dtest=AboutPageBodyTest,CuratedPagePublicationKindTest` — 16 passed, 0 failures, 0 errors.
- After changes: `mvn -f publication-exporter/pom.xml test` — 781 passed, 0 failures, 0 errors.

## Concerns

- `graphify update .` was attempted after the code changes but failed with macOS `Operation not permitted`; the source graph may need a later refresh.
- Maven continues to emit the repository's existing JUnit temporary-directory symlink cleanup warnings; they did not cause test failures.
- The inherited Markdown projection is intentionally unchanged and is not suitable for site installation of curated-page JSON until Task 5.

## Review-fix report

### Fixed

- `AboutPageBody` now rejects a second occurrence of any required H2 heading and names the duplicated heading in `MalformedBodyException`.
- About-page prose now follows the G7 editorial compatibility shape: consecutive non-blank lines join with one space and separate paragraphs join with `\n\n` for summary, eyebrow, lead, colophon, and principle text.
- `requiredPrinciples` is composed from named section slicing, principle collection, heading recognition, boundary discovery, prose parsing, validation, and cardinality helpers.
- `CuratedPagePublicationKind` now rejects present non-boolean `publicSearchable` values with a field diagnostic, represents the field as an optional BOOLEAN in its contract, and no longer claims `description` is blocked.
- Added typed boolean parsing to the Markdown frontmatter boundary without changing the existing `flag` behavior used by shared publish admission.

### New and changed tests

- `AboutPageBodyTest`: parameterized duplicate-heading rejection for all five required headings, plus multi-paragraph preservation across every prose field and both principles.
- `CuratedPagePublicationKindTest`: invalid `publicSearchable` diagnostic and optional-BOOLEAN/no-blocked-description contract assertions.
- `CuratedPagePublicationKindFixtures`: blocked `publicSearchable: not-a-boolean` fixture.

### Verification counts

- Before this fix: 781 passed, 0 failures, 0 errors (the pre-fix full-suite count recorded above).
- Focused after-fix verification: 27 passed, 0 failures, 0 errors in `AboutPageBodyTest`, `CuratedPagePublicationKindTest`, and `FieldContractTest`.
- After this fix: `mvn -f publication-exporter/pom.xml test` — 790 passed, 0 failures, 0 errors.

### Verification concern

- `graphify update .` was attempted after the source changes but remains blocked by macOS `Operation not permitted` during graph rebuild; the graph may need a later refresh.
