# Task 2 report — `ConceptPublicationKind`

## Status

`DONE_WITH_CONCERNS`

## Commit

- `ea94bcc` — `feat(publication-exporter): add concept publication kind`

## Delivered

- Added final `ConceptPublicationKind` for `concepts/concept` with the required
  collection, content type, and `/concepts/` route prefix.
- Implemented admission for the required identity fields and optional
  `notThis`, `relations`, and `examples` fields.
- Flattened translated list values into deterministic ordered `PublicField`
  entries using the specified bracket-key convention.
- Added the required relation/example structural guards and copied the sibling
  publication-kind slug/non-blank helper logic.
- Added the complete concept `KindContract`, including optional
  `nonBlank`, `nonBlankStringList`, and `nonBlankStructuredList` fields.
- Registered `ConceptPublicationKind` in `PublicationKinds.installed()`.
- Added `ConceptPublicationKindTest`, reusing
  `ConceptPublicationKindFixtures.all()` rather than duplicating its fixture
  table.
- Updated the installed-kind CLI contract expectation from four to five kinds;
  this was the only stale existing expectation surfaced by the full suite.

## Verification

- Expected RED phase: `ConceptPublicationKindTest` initially failed to compile
  because `ConceptPublicationKind` did not exist.
- Focused command:
  `mvn -f publication-exporter/pom.xml test -Dtest=ConceptPublicationKindTest,FieldContractTest,PublicationContractConformanceTest`
  - Final result: `BUILD SUCCESS`
  - 56 tests, 0 failures, 0 errors.
- Full command:
  `mvn -f publication-exporter/pom.xml test`
  - Final result: `BUILD SUCCESS`
  - 715 tests, 0 failures, 0 errors, 0 skipped.

## Concerns

- `graphify update .` was attempted after the code changes, but its incremental
  rebuild failed with `Operation not permitted` in the environment. The prior
  graph query completed successfully, and this did not affect compilation or
  test verification.
- Pre-existing unrelated `.haft` notes/problem data and the untracked
  `openspec/changes/s17d-concepts-concept-kind/` directory were left untouched
  and unstaged.
