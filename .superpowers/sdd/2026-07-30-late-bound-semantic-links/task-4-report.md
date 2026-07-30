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
