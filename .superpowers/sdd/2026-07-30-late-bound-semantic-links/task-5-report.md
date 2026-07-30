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
