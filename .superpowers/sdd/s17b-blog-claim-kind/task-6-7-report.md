# S17b Tasks 6-7 Report

Status: DONE_WITH_CONCERNS

## Implemented

- Added `BlogClaimAcceptanceTest`, exercising `blog/claim` through the existing admission, prepare, mark-reviewed, build-from-review, and filesystem site-install paths.
- Added and registered `ClaimPublicationKind` with `blog/claim` identity, `claims` routing, and a non-blank `statement` contract.
- Preserved canonical public-field order as `title`, `description`, `statement`.
- Added optional `supports`/`opposes`/`assumes`/`refines`/`contradicts`/`sources` list-of-map reading to `MarkdownNote` and kind-owned YAML rendering through `YamlScalar.doubleQuoted(...)`.
- Kept relationship/source values opaque: no semantic target resolution, routing, or translation was added.
- Generalized the admitted-publication bridge so ordered fields, including `statement`, reach the already-generic translation, snapshot, approval, and installation path. `PrepareHandler` and `MarkReviewedHandler` remain the existing handlers and now consume `NoteIntake.Result.fields()`.
- Added `ClaimPublicationKindTest` coverage for accepted structured data, missing/blank statement, optional empty relationship/source arrays, ordered fields, and scalar escaping.
- Updated stale pre-claim tests to use `blog/book` as the unsupported kind and to find essay/note contracts by content type now that `PublicationKinds.installed()` contains three kinds. Task 8's claim contract-conformance fixtures remain unimplemented.
- Marked OpenSpec tasks 6.1-7.5 complete.

No production adapter was added. No file under `exporter-java/` or the production release package was edited.

## TDD evidence

Command run from `publication-exporter/`:

- RED: `mvn -q test -Dtest=BlogClaimAcceptanceTest` failed with one assertion failure and the admission diagnostic `publicCollection/publicContentType is not a supported publication kind` on field `publicContentType`.
- GREEN: `mvn -q test -Dtest=BlogClaimAcceptanceTest` passed after `ClaimPublicationKind` was registered and the field/structured-data bridge was completed.

Before the valid RED, two test-authoring mistakes were corrected without adding production behavior: one referenced a nonexistent `ReleaseResult.diagnostics()` method, and one fixture included two YAML arrays before the minimal parser existed, causing the whole frontmatter to be treated as malformed. Neither invalid run is claimed as the RED witness.

## Verification

Commands run from `publication-exporter/` unless noted:

- `mvn -q test -Dtest=ClaimPublicationKindTest,MarkdownNoteTest` — passed (35 tests, 0 failures, 0 errors).
- `mvn -q test -Dtest=BlogClaimAcceptanceTest` — passed (1 test, 0 failures, 0 errors).
- `mvn -q test` — passed (606 tests, 0 failures, 0 errors, 0 skipped).
- `git diff --check` from the repository root — passed.

The full suite emitted only JUnit temporary-directory warnings about deleting symlinks without deleting their external targets in the existing confinement tests.

`graphify update .` was attempted from the repository root after source edits. Code re-extraction started, but the rebuild failed with `[Errno 1] Operation not permitted`; the graph refresh is not claimed as successful.

## Design review

- Elegant Objects: `ClaimPublicationKind` is final and immutable, structured values are copied into immutable list/map carriers, and tests use existing null implementations rather than interaction mocks.
- SBPP: claim admission and YAML rendering use intention-revealing selectors and composed methods; collection types communicate order explicitly.
- Nullables: acceptance coverage keeps the real application path active and substitutes only existing nulled infrastructure implementations; site projection itself uses the real filesystem installer under `@TempDir`.
- OO Design Guide: kind-specific validation and YAML shape remain in `ClaimPublicationKind`; the generic site installer receives the already-rendered opaque fragment and remains unaware of claim relationships.

## Files changed

Production:

- `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/AdmittedPublication.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/ClaimPublicationKind.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/PublicationKinds.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/intake/NoteIntake.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/note/MarkdownNote.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`

Tests:

- `publication-exporter/src/test/java/dev/eugene/publicationexporter/BlogClaimAcceptanceTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/ClaimPublicationKindTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/WritePublicationContractCliAcceptanceTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/contract/PublicationContractConformanceTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/intake/NoteIntakeTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/note/MarkdownNoteTest.java`

Artifacts:

- `openspec/changes/s17b-blog-claim-kind/tasks.md`
- `.superpowers/sdd/s17b-blog-claim-kind/task-6-7-report.md`

The pre-existing modification to `.superpowers/sdd/s17b-blog-claim-kind/task-3-report.md` was preserved and is excluded from this task's commit.

## Concerns

- Graphify refresh remains environmentally blocked by `Operation not permitted`.
- Task 8 still owns claim-specific publication-contract conformance fixtures and explicit claim contract assertions; this slice only made the existing essay/note contract tests compatible with the newly installed third kind.

## Fix round 1 — nested claim metadata and parser composition

Status: DONE_WITH_CONCERNS

### Addressed

- `MarkdownNote` now retains populated block-list YAML in source order, so site-supported `sources` entries with nested `link` objects and `evidence`/`locator` rich-text arrays pass admission and reach the installed RU site frontmatter without flattening.
- Structured-field shape remains observable after parsing. Scalar, indented-map, and inline-map values supplied for relationship fields or `sources` block `blog/claim` admission with diagnostics naming the offending field and required list shape; they are no longer treated as absent/empty.
- Every populated relationship entry must be a scalar map containing a non-blank `label` and at most the optional opaque `target`. Unknown keys, missing/blank labels, and nested values block the relationship field before projection. Accepted targets remain opaque and are emitted through `YamlScalar.doubleQuoted(...)`.
- `MarkdownNote.parseHeader(...)` now delegates each field to named record steps, and `parseMapList(...)` composes named boundary, source-line, and list-shape helpers. The small `ScalarMapListParser` keeps per-entry scanning at one abstraction level while the opaque source carrier stays scoped to the already-supported block structure.
- The acceptance fixture now includes nested `sources.link`, `sources.evidence`, and `sources.locator` data and verifies that the real `FilesystemManagedSiteInstaller` carries that YAML into the installed claim.

No Task 8 contract-conformance fixture or assertion was added. No file under `exporter-java/` or the production release package was changed, and semantic resolution/translation behavior was not altered.

### TDD evidence

Commands run from `publication-exporter/`:

- RED: `mvn -q test -Dtest=MarkdownNoteTest,ClaimPublicationKindTest,BlogClaimAcceptanceTest` failed because nested `sources` made the header `MALFORMED`, blocking claim admission and the end-to-end fixture.
- GREEN: the same focused command passed after retaining nested source YAML and projecting it opaquely.
- RED: `mvn -q test -Dtest=MarkdownNoteTest,ClaimPublicationKindTest` failed on non-list relationship/source shapes plus missing, blank, and undeclared relationship fields.
- GREEN: the focused parser/admission/acceptance command passed after adding structured-shape diagnostics and relationship validation.
- RED/GREEN edge: `ClaimPublicationKindTest#relationshipEntriesRejectStructuredValues` and `ClaimPublicationKindTest#inlineMappingSourceMetadataBlocksClaimAdmission` each failed for the expected silent-acceptance/wrong-diagnostic behavior, then passed after the corresponding guards.

### Verification

- `mvn -q test -Dtest=MarkdownNoteTest,ClaimPublicationKindTest,BlogClaimAcceptanceTest` — passed (45 tests, 0 failures, 0 errors).
- `mvn -q test` — passed (615 tests, 0 failures, 0 errors, 0 skipped).
- `git diff --check` — passed before this report append and will be rerun as the final delivery gate.
- `graphify update .` — attempted after the source changes; rebuild failed with `[Errno 1] Operation not permitted`, so no successful graph refresh is claimed.

### Files changed in fix round 1

- `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/ClaimPublicationKind.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/note/MarkdownNote.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/BlogClaimAcceptanceTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/ClaimPublicationKindTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/note/MarkdownNoteTest.java`
- `.superpowers/sdd/s17b-blog-claim-kind/task-6-7-report.md`

### Concerns

- Graphify refresh remains environmentally blocked by `Operation not permitted`.
- Task 8 remains intentionally unimplemented in this fix round.
