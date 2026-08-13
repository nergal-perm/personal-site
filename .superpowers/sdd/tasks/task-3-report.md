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
