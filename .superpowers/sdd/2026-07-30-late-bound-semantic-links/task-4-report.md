# Task 4 Report: Atomic Approved Triples and Approval Integration

## Status

DONE_WITH_CONCERNS

## Implemented

- Extended `PublishedSnapshotStore` from pair-only approved snapshots to explicit mode-specific staging:
  - `stageSemantic(Path, byte[], byte[], byte[])` writes and commits `ru.md`, `en.md`, and `references.json` as one atomic snapshot.
  - `stageLegacy(Path, byte[], byte[])` remains pair-only for `SemanticSchemaState.Mode.LEGACY`.
  - Semantic staging rejects live two-file layouts; legacy staging rejects live three-file layouts.
  - Layout validation rejects missing, extra, symbolic, non-regular, and multiply hard-linked published leaves.
  - Commit visibility checks now compare the full semantic triple.
- Updated `ReviewWorkspace` and `CommandServices` so approval staging receives exact `(collection, publicId, ru, en, references)` bytes.
- Updated `mark-reviewed` semantic approval integration:
  - Acquires a shared `SemanticOperationLock` for semantic approval.
  - Safe-reads candidate `ru.md`, `en.md`, and `references.json`.
  - Rewrites only EN `translationStatus` to reviewed.
  - Rebinds `references.json` hashes to the candidate RU bytes and reviewed EN bytes.
  - Atomically replaces the candidate triple.
  - Stages and commits the exact reviewed candidate triple to `published/`.
  - Revalidates source identity and candidate leaves before published commit.
  - Updates source workflow only after the approved snapshot is durable.
  - Reports workflow reconciliation diagnostics if the source workflow update fails after the approved snapshot is durable.
- Updated `ReviewLaunchPlanner`:
  - In semantic mode, published baselines must include a valid `references.json` with RU/EN hashes matching the published markdown bytes.
  - Partial or invalid semantic published triples return `published_snapshot_inconsistent`.
  - The sidecar is never exposed as an editor target.
- Fixed a bridge-output ordering issue: success JSON is now emitted only after `PendingSnapshot.close()` cannot report a recovery condition.
- Tightened workflow guard ordering so `services.clock().instant()` is captured after explicit snapshot checks.

## TDD Evidence

RED:

```text
mvn -q -Dtest=PublishedSnapshotStoreTest test
COMPILATION ERROR: cannot find symbol method stageSemantic(Path, byte[], byte[], byte[])
```

After adding the production API, the focused store suite reached the intended behavioral red:

```text
PublishedSnapshotStoreTest.rejectsLegacyPartialOrExtraPublishedLayoutsInSemanticMode
Expected IllegalArgumentException to be thrown, but nothing was thrown.
```

GREEN:

```text
mvn -q -Dtest=PublishedSnapshotStoreTest test
exit code 0
```

Full required selected suite:

```text
mvn -q -Dtest=PublishedSnapshotStoreTest,ReviewWorkspaceTest,ReviewLaunchPlannerTest,AstroExportCommandTest test
exit code 0
```

Extra verification:

```text
mvn -q test
exit code 0
```

Whitespace:

```text
git diff --check
exit code 0
```

Note: Maven/JNA emitted the existing Java native-access warning during tests.

## Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/review/PublishedSnapshotStore.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewLaunchPlanner.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/PublishedSnapshotStoreTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewLaunchPlannerTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`

## Self-Review Findings

- `CandidateSnapshotStore` rechecks guards after its own visible swap, so candidate leaves cannot be passed as commit guards for candidate replacement. I kept explicit candidate triple checks before and after replacement, and use source guards for the candidate store commit.
- The task brief says candidate replacement is guarded by original candidate leaves. The current candidate store guard semantics make that literal guard list incompatible with replacing candidate leaves; satisfying it exactly would require changing `CandidateSnapshotStore` guard semantics or adding a pre-swap-only guard API. I did not broaden that store in this task.
- No referrer snapshot directories are enumerated or written by approval. Impact counts remain zero through the existing bridge behavior until the Task 6 interface exists.

## Concerns

- The candidate replacement cannot use candidate leaves as `CandidateSnapshotStore` commit guards because those guards are checked after the visible swap. The implementation compensates with explicit candidate triple checks at approval boundaries.

---

# Fix Round 1 Report

## Status

DONE

## Findings Fixed

- Critical 1: semantic `mark-reviewed` now validates and derives `reviewedBytes` from `candidate/en.md`, not root `<page>/en.md`. The published semantic EN release input is the reviewed candidate EN bytes.
- Critical 2: `CandidateSnapshotStore` now supports separate pre-swap and post-swap guards. Approval replacement passes source plus original candidate leaves as pre-swap guards and source as the post-swap guard, so replaced candidate leaves can still be guarded without failing the post-swap visibility check.
- Important 3: `mark-reviewed` now blocks `SemanticSchemaState.Mode.MIGRATION_INCOMPLETE` before approval, and `ReviewWorkspace.stageApprovedSnapshot` rejects incomplete migration instead of falling back to legacy staging.
- Important 4: semantic approval now closes the shared semantic lease when published snapshot staging throws either recovery or runtime failures.

## Covering Tests Added

- `CandidateSnapshotStoreTest.preSwapGuardsCanProtectLeavesThatAreReplaced`
- `AstroExportCommandTest.semanticApprovalUsesCandidateEnglishAsReleaseInput`
- `AstroExportCommandTest.markReviewedBlocksWhenSemanticMigrationIsIncomplete`
- `AstroExportCommandTest.semanticLeaseClosesWhenPublishedStagingFails`

## TDD Evidence

RED:

```text
mvn -q -Dtest=CandidateSnapshotStoreTest,AstroExportCommandTest test
[ERROR] COMPILATION ERROR :
[ERROR] CandidateSnapshotStoreTest.java:[76,14] method commit in interface CandidateSnapshotStore.PendingCandidate cannot be applied to given types;
required: java.util.List<WorkflowStateService.SnapshotGuard>
found: java.util.List<WorkflowStateService.SnapshotGuard>,java.util.List<java.lang.Object>
reason: actual and formal argument lists differ in length
exit code 1
```

GREEN / covering tests:

```text
mvn -q -Dtest=CandidateSnapshotStoreTest,AstroExportCommandTest,ReviewWorkspaceTest test
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
exit code 0
```

Broader selected suite:

```text
mvn -q -Dtest=PublishedSnapshotStoreTest,CandidateSnapshotStoreTest,ReviewWorkspaceTest,ReviewLaunchPlannerTest,AstroExportCommandTest,PrepareWorkflowTest test
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
exit code 0
```

Full Java suite:

```text
mvn -q test
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled

Usage: astro-export [-hV] [--dry-run] [--out=<out>] [--report=<report>]
                    [--review=<review>] [--vault=<vault>] [COMMAND]
Export explicitly published Obsidian notes into Astro source trees.
      --dry-run           select and report without writing content
  -h, --help              Show this help message and exit.
      --out=<out>         Astro project root for atomic write mode
      --report=<report>   report path
      --review=<review>   translation review workspace
  -V, --version           Print version information and exit.
      --vault=<vault>     Obsidian vault root
Commands:
  build-from-review
  migrate-overrides
  prepare
  inspect-publication
  mark-reviewed
  refresh-publication-queue
  write-publication-contract
exit code 0
```

Whitespace:

```text
git diff --check
exit code 0
```

## Files Changed In Fix Round 1

- `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/CandidateSnapshotStore.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/prepare/PrepareWorkflowTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/CandidateSnapshotStoreTest.java`
- `.superpowers/sdd/2026-07-30-late-bound-semantic-links/task-4-report.md`

## Concerns

- Maven/JNA still emits the existing native-access warning under the current JDK; tests exit 0.
