# Task 6 Report: Late-Bound Release Projection, Reverse Impact, and Unpublish

## Status

DONE

## Implemented

- Added `ApprovedTargetRegistry` with immutable approved target lookup by `pageRef`.
- Added registry validation for complete target identity and unique `pageRef`, `publicId`, RU route, and EN route.
- Added `ReferenceImpactIndex` deriving `targetRef -> inbound occurrences` without writing referrers.
- Added `ReleaseInputGuard` with byte-exact verification for required selected source files and optional approved/cross-input files.
- Added `ApprovedReleaseMaterializer` that:
  - materializes `List<ApprovedPageSnapshot>` into a bilingual `ManifestResult`;
  - late-binds semantic `ref:` destinations only when the target snapshot is approved;
  - strips private/unpublished targets to approved labels;
  - preserves referrer approved hashes;
  - builds activation audit data in sidecar order;
  - collects assets from approved RU Markdown;
  - rejects leaked `ref:` destinations, `vault-ref-*` ids, catalog paths, `authoredTarget`, and wrong-locale routes.
- Extended `ApprovedPageSnapshot` with backwards-compatible `InputFiles` provenance.
- Wired `ApprovedSnapshotRepository` to attach approved RU/EN/reference leaf paths, plus the catalog path when catalog rename reconciliation supplied the snapshot.

## TDD Evidence

Red 1:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest,ReferenceImpactIndexTest test
```

Result: failed at test compilation because `ApprovedReleaseMaterializer` and `ReferenceImpactIndex` did not exist.

Red 2:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest,ReferenceImpactIndexTest test
```

Result: `registryRejectsDuplicateApprovedRoutes` failed because duplicate approved routes were not rejected.

Red 3:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest test
```

Result: failed at test compilation because `ApprovedPageSnapshot.InputFiles` did not exist for approved-leaf guard provenance.

Green:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest,ReferenceImpactIndexTest,ApprovedSnapshotRepositoryTest,SemanticReferenceMarkdownTest test
```

Result: exit 0.

Final required verification:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest,ReferenceImpactIndexTest,SemanticReferenceMarkdownTest test
```

Result: exit 0.

Whitespace check:

```bash
git diff --check -- exporter-java/src/main/java/dev/eugene/astroexport/release exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedPageSnapshot.java exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedSnapshotRepository.java exporter-java/src/test/java/dev/eugene/astroexport/release
```

Result: exit 0.

## Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedTargetRegistry.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/release/ReferenceImpactIndex.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/release/ReleaseInputGuard.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializer.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedPageSnapshot.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedSnapshotRepository.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializerTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/release/ReferenceImpactIndexTest.java`

## Self-Review Findings

- Fixed duplicate target occurrence handling so equal reference values still use occurrence ids and sidecar order.
- Preserved insertion order in immutable registry/audit/index maps instead of using `Map.copyOf`.
- Tightened selected source guarding from best-effort to required safe regular file capture.
- Added approved-leaf provenance to snapshots so the materializer can abort after approved snapshot replacement.

## Concerns

- None.

# Fix Round 1 Report

## Status

DONE

## Findings Fixed

1. Bound approved leaf bytes at repository load time instead of recapturing them only during materialization.
2. Added public-output rejection for absolute/local vault paths in rendered body and metadata.

## What Changed

- `ApprovedPageSnapshot.InputFiles` now carries `InputFile(path, bytes)` records.
- `ApprovedSnapshotRepository` stores the exact approved `ru.md`, `en.md`, and `references.json` bytes read during `loadSelected`; when catalog rename reconciliation has an on-disk catalog file, it captures that byte payload too.
- `ReleaseInputGuard.Builder.capture(InputFile)` compares current bytes with repository-captured bytes immediately during `materialize`, then keeps the same bytes for later `verify()`.
- `ApprovedReleaseMaterializer` now uses repository-bound approved input bytes and rejects local/vault path leaks through `VAULT_PATH`.
- Added regression coverage for:
  - replacing an approved leaf after repository load but before materialization;
  - leaking `/Users/eugene/Documents/personal-wiki/knowledge-base/...` into public output.

## TDD Evidence

Red:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest,ApprovedSnapshotRepositoryTest test
```

Output:

```text
[ERROR] Tests run: 13, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.118 s <<< FAILURE! -- in dev.eugene.astroexport.release.ApprovedReleaseMaterializerTest
[ERROR] dev.eugene.astroexport.release.ApprovedReleaseMaterializerTest.publicOutputGateRejectsAbsoluteVaultPaths -- Time elapsed: 0.004 s <<< FAILURE!
org.opentest4j.AssertionFailedError: Expected dev.eugene.astroexport.release.ApprovedReleaseException to be thrown, but nothing was thrown.

[ERROR] Tests run: 15, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.107 s <<< FAILURE! -- in dev.eugene.astroexport.review.ApprovedSnapshotRepositoryTest
[ERROR] dev.eugene.astroexport.review.ApprovedSnapshotRepositoryTest.guardRejectsApprovedLeafReplacedAfterRepositoryLoad -- Time elapsed: 0.006 s <<< FAILURE!
org.opentest4j.AssertionFailedError: Expected dev.eugene.astroexport.release.ApprovedReleaseException to be thrown, but nothing was thrown.
```

Green focused:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest,ApprovedSnapshotRepositoryTest test
```

Output:

```text
<no output; exit 0>
```

Covering tests:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest,ReferenceImpactIndexTest,ApprovedSnapshotRepositoryTest,SemanticReferenceMarkdownTest test
```

Output:

```text
<no output; exit 0>
```

Whitespace check:

```bash
git diff --check -- exporter-java/src/main/java/dev/eugene/astroexport/release exporter-java/src/main/java/dev/eugene/astroexport/review exporter-java/src/test/java/dev/eugene/astroexport/release exporter-java/src/test/java/dev/eugene/astroexport/review
```

Output:

```text
<no output; exit 0>
```

## Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializer.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/release/ReleaseInputGuard.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedPageSnapshot.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedSnapshotRepository.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializerTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/ApprovedSnapshotRepositoryTest.java`

## Concerns

- None.

---

# Fix Round 2 Report

## Status

DONE

## Findings Fixed

1. Replaced the hardcoded `VAULT_PATH` heuristic with a `vaultRoot`-aware output gate.
2. Stopped rejecting arbitrary root-relative public links such as `/about/`, `/feed.xml`, and `/podcast/`.

## What Changed

- `ApprovedReleaseMaterializer.materialize(...)` now passes `vaultRoot` into public-output validation.
- Removed the broad root-relative path heuristic that rejected valid public routes.
- Added path-token detection that rejects path-like absolute values only when their normalized path starts with the normalized `vaultRoot`.
- Added regression coverage for:
  - `/ru/private/Note.md` with `vaultRoot=/ru`;
  - valid public root-relative links `/about/`, `/feed.xml`, and `/podcast/`;
  - the existing `/Users/...` vault-path case now explicitly supplies that directory as `vaultRoot`.

## TDD Evidence

Red:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest test
```

Output:

```text
[ERROR] Tests run: 15, Failures: 1, Errors: 1, Skipped: 0, Time elapsed: 0.124 s <<< FAILURE! -- in dev.eugene.astroexport.release.ApprovedReleaseMaterializerTest
[ERROR] dev.eugene.astroexport.release.ApprovedReleaseMaterializerTest.publicOutputGateAllowsPublicRootRelativeLinksOutsideVaultRoot -- Time elapsed: 0.003 s <<< ERROR!
dev.eugene.astroexport.release.ApprovedReleaseException: ru output contains private semantic payload
[ERROR] dev.eugene.astroexport.release.ApprovedReleaseMaterializerTest.publicOutputGateRejectsPathsInsideVaultRootEvenWhenTheyLookLikeLocaleRoutes -- Time elapsed: 0.002 s <<< FAILURE!
org.opentest4j.AssertionFailedError: Expected dev.eugene.astroexport.release.ApprovedReleaseException to be thrown, but nothing was thrown.
```

Green focused:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest test
```

Output:

```text
<no output; exit 0>
```

Covering tests:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest,ReferenceImpactIndexTest,ApprovedSnapshotRepositoryTest,SemanticReferenceMarkdownTest test
```

Output:

```text
<no output; exit 0>
```

Whitespace check:

```bash
git diff --check -- exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializer.java exporter-java/src/test/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializerTest.java
```

Output:

```text
<no output; exit 0>
```

## Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializer.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializerTest.java`

## Concerns

- None.

---

# Fix Round 3 Report

## Status

DONE

## Finding Fixed

1. Public-output vault-path detection no longer requires an allowlisted file extension on the final path component.

## What Changed

- Removed the `PATH_LIKE_LEAF` suffix gate from `ApprovedReleaseMaterializer.containsVaultPath(...)`.
- The public-output gate now compares every absolute path token against the normalized `vaultRoot`, including directory tokens and extensionless leaves.
- Extended regression coverage for `/ru/private/Note.md`, `/ru/private/`, and `/ru/private/Secret` with `vaultRoot=/ru`.

## TDD Evidence

Initial root command:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest test
```

Output:

```text
[ERROR] The goal you specified requires a project to execute but there is no POM in this directory (/Users/eugene/Dev/personal-site). Please verify you invoked Maven from the correct directory. -> [Help 1]
[ERROR]
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR]
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MissingProjectException
```

Focused green:

```bash
mvn -q -f exporter-java/pom.xml -Dtest=ApprovedReleaseMaterializerTest test
```

Output:

```text
<no output; exit 0>
```

Covering tests:

```bash
mvn -q -f exporter-java/pom.xml -Dtest=ApprovedReleaseMaterializerTest,ReferenceImpactIndexTest,ApprovedSnapshotRepositoryTest,SemanticReferenceMarkdownTest test
```

Output:

```text
<no output; exit 0>
```

Whitespace check:

```bash
git diff --check -- exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializer.java exporter-java/src/test/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializerTest.java .superpowers/sdd/2026-07-30-late-bound-semantic-links/task-6-report.md
```

Output:

```text
<no output; exit 0>
```

## Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializer.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializerTest.java`
- `.superpowers/sdd/2026-07-30-late-bound-semantic-links/task-6-report.md`

## Concerns

- None.
