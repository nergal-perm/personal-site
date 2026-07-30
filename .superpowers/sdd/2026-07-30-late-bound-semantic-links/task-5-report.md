# Task 5 Report: Approved Snapshot Repository and Selection Gate

## Status

DONE

## Implemented

- Added `ApprovedSnapshotRepository.loadSelected(SelectionResult, Path, VaultReferenceCatalog)`.
- Added approved release input records:
  - `ApprovedPageSnapshot`
  - `SnapshotHashes`
- Added `ApprovedReleaseException` with `code()` and `sourcePath()`.
- Added package-visible `ReviewWorkspace.parseApprovedMarkdown(byte[], String, String, String)`.
- The repository now:
  - builds the selected source-path set from included notes and excluded publish-true candidates;
  - scans only `review/<collection>/<publicId>/published/` triples;
  - ignores `candidate/` triples for release input;
  - matches selected paths by exact sidecar `sourcePath`;
  - reconciles renames only through one active catalog entry and one approved sidecar `pageRef`;
  - blocks missing, ambiguous, invalid, unsafe, and migration-incomplete release inputs;
  - validates `references.json` hashes and semantic reference order through `PageReferenceMapCodec`;
  - parses approved RU/EN Markdown strictly through the shared ReviewWorkspace YAML parser;
  - requires RU `language: ru`, EN `language: en`, matching IDs, matching content types, and EN `translationStatus: reviewed`;
  - rejects invalid UTF-8 before approved Markdown parsing;
  - derives `ManifestEntry` target paths and routes from approved collection, approved ID, content type, and language;
  - rejects duplicate page refs, public IDs, target paths, and routes before returning release input.

## TDD Evidence

Red command:

```bash
mvn -q -Dtest=ApprovedSnapshotRepositoryTest test
```

Red result:

- Failed at test compilation because `dev.eugene.astroexport.release.ApprovedReleaseException`, `ApprovedSnapshotRepository`, and `ApprovedPageSnapshot` did not exist.

Green command:

```bash
mvn -q -Dtest=ApprovedSnapshotRepositoryTest test
```

Green result:

- Passed: 14 repository tests.

Required combined command:

```bash
mvn -q -Dtest=ApprovedSnapshotRepositoryTest,ReviewWorkspaceTest test
```

Required combined result:

- Passed.
- Output included the existing JNA native-access warning:
  `WARNING: java.lang.System::load has been called by com.sun.jna.Native`.

## Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedReleaseException.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedPageSnapshot.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedSnapshotRepository.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/SnapshotHashes.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/ApprovedSnapshotRepositoryTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java`

## Self-Review Findings

- `git diff --check` passed.
- The implementation keeps current draft metadata out of released identity and wording: included/excluded selection entries provide only the selected source paths.
- `candidate/` triples are intentionally ignored, so pending snapshots cannot replace approved `published/` triples.
- Approved Markdown `route` and `targetPath` frontmatter is retained in metadata for diagnostics/duplicate gates, but the returned `ManifestEntry` route and target path accessors are derived by the parser.

## Concerns

- None.

---

# Fix Round 1/5 Report

## Status

DONE

## Findings Fixed

1. `ReviewWorkspace.parseApprovedMarkdown` now rejects control-field value aliases such as `translationStatus: *ok` before YAML loading accepts the resolved scalar.
2. `ApprovedSnapshotRepository` duplicate checks now use only derived release `ManifestEntry` route and target-path values, not stored/tampered diagnostic `route` or `targetPath` frontmatter.

The deferred Minor finding about `ManifestEntry` metadata mutability was not changed.

## Covering Tests

- Added `ReviewWorkspaceTest.approvedMarkdownRejectsControlFieldValueAliases`.
- Updated repository coverage so duplicate stored `route` and `targetPath` metadata is accepted when derived release routes/target paths differ.

## TDD Evidence

Red command:

```bash
mvn -q -Dtest=ApprovedSnapshotRepositoryTest,ReviewWorkspaceTest test
```

Red output:

```text
[ERROR] Tests run: 14, Failures: 0, Errors: 2, Skipped: 0, Time elapsed: 0.170 s <<< FAILURE! -- in dev.eugene.astroexport.review.ApprovedSnapshotRepositoryTest
[ERROR] dev.eugene.astroexport.review.ApprovedSnapshotRepositoryTest.duplicateStoredTargetPathDoesNotBlockWhenDerivedTargetPathsDiffer -- Time elapsed: 0.007 s <<< ERROR!
dev.eugene.astroexport.release.ApprovedReleaseException: duplicate approved release key: src/content/blog/ru/a.md
[ERROR] dev.eugene.astroexport.review.ApprovedSnapshotRepositoryTest.duplicateStoredRouteDoesNotBlockWhenDerivedRoutesDiffer -- Time elapsed: 0.006 s <<< ERROR!
dev.eugene.astroexport.release.ApprovedReleaseException: duplicate approved release key: /ru/essays/a/
[ERROR] Tests run: 31, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.419 s <<< FAILURE! -- in dev.eugene.astroexport.review.ReviewWorkspaceTest
[ERROR] dev.eugene.astroexport.review.ReviewWorkspaceTest.approvedMarkdownRejectsControlFieldValueAliases -- Time elapsed: 0.002 s <<< FAILURE!
org.opentest4j.AssertionFailedError: Expected java.lang.IllegalArgumentException to be thrown, but nothing was thrown.
[ERROR] Tests run: 45, Failures: 1, Errors: 2, Skipped: 0
```

Green command:

```bash
mvn -q -Dtest=ApprovedSnapshotRepositoryTest,ReviewWorkspaceTest test
```

Green output:

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Exit code: 0.

Additional check:

```bash
git diff --check
```

Output: no output, exit code 0.

## Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedSnapshotRepository.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/ApprovedSnapshotRepositoryTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java`
- `.superpowers/sdd/2026-07-30-late-bound-semantic-links/task-5-report.md`

## Concerns

- None.
