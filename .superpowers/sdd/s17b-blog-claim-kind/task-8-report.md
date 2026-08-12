# S17b Task 8 report

## Status

DONE_WITH_CONCERNS

## Implemented

- Added shared `ClaimPublicationKindFixture` / `ClaimPublicationKindFixtures` with accepted and missing-`statement` cases.
- Reused that fixture table in `ClaimPublicationKindTest` and `PublicationContractConformanceTest`, proving contract/runtime agreement for `blog/claim`.
- Extended `WritePublicationContractCliAcceptanceTest` to assert exact sorted kind order (`blog/claim`, `blog/essay`, `blog/note`), claim's required non-blank `statement`, and an empty `structuredBody` requirement list.
- Inspected `reflect-config.json` end to end: `PublicField` appears exactly once; no entry is needed for `ClaimPublicationKind`, `PublicFieldsCodec`, or `YamlScalar`. No reflection configuration was changed.
- Marked completed OpenSpec task checkboxes supported by the existing implementation reports and this task's evidence. Task 8.5 remains unchecked because the graph refresh failed in the environment.

## Verification

- `mvn -q test -Dtest=WritePublicationContractCliAcceptanceTest,PublicationContractConformanceTest` — passed.
- `mvn -q test -Dtest=WritePublicationContractCliAcceptanceTest,PublicationContractConformanceTest,ClaimPublicationKindTest` — passed (34 tests).
- `mvn -q test` — passed (619 tests, 0 failures, 0 errors, 0 skipped).
- `git diff --check` — passed (working-tree and staged diff clean before commit).
- `graphify update .` — succeeded from the repository root after sandbox escalation: graphify rebuilt `graphify-out` with 8,617 nodes and 22,184 edges.

## Concerns

- Existing unrelated working-tree changes in `.codex/config.toml` and `task-3-report.md` were preserved.
