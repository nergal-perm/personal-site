# Task 7 Report: Approved-Only CLI, Independent State Dimensions, and Reports

## Implemented

- Added `CommandServices.buildApprovedRelease(Path vault, Path review)` using selection, `VaultReferenceCatalog.loadIfPresent`, `ApprovedSnapshotRepository.loadSelected`, and `ApprovedReleaseMaterializer.materialize`.
- Routed semantic-mode non-dry-run `build-from-review` and root write mode through approved release materialization instead of current generated/review candidates.
- Preserved legacy write behavior before the semantic activation marker exists, and blocked `MIGRATION_INCOMPLETE` with `migration-incomplete`.
- Held a shared `SemanticOperationLock` lease from before schema-mode read through materialization, Astro validation, and site commit/rollback.
- Added `SiteWriter.CommitGuard` and passed the approved release input guard into atomic writes.
- Invoked the commit guard after Astro validation, before first live managed-tree movement, before and after each managed-root move, and after all roots are installed before cleanup.
- Mapped commit guard failures through the rollback path with `concurrent-approved-snapshot-change` in the writer error.
- Incremented bridge schema to version 3 and inserted nullable fields after `translationStatus`: `candidateState`, `approvedSnapshotState`, `semanticReferencesState`, `releaseState`.
- Derived inspect states independently:
  - candidate: `absent`, `generated`, `reviewed`, `stale`
  - approved snapshot: `absent`, `valid`, `invalid`
  - semantic references: `valid`, `migration-required`, `invalid`
  - release: `releasable`, `blocked`
- Kept compatibility fields `pairFreshness` and `translationStatus`.
- Added release reports for ignored semantic draft targets, separate from blocking diagnostics.
- Added approved-release diagnostic-code reporting and stable code vocabulary in blocked write reports.
- Added semantic `mark-reviewed` success summary using the derived reverse index:
  - `inboundLinksActivated`
  - `affectedApprovedPages`
  - `pendingDraftReferrers`
- Added `VaultReferenceCatalog.loadIfPresent(Path)` as the brief-named compatibility entrypoint over the existing empty-if-absent loader.

## TDD Evidence

Red tests added first:

```text
mvn -q -Dtest=AstroExportCommandTest#buildFromReviewBlocksSelectedUnapprovedNoteInSemanticMode+buildFromReviewIgnoresFreshGeneratedCandidateWhenApprovedExists test
```

Initial result:

```text
Tests run: 2, Failures: 2, Errors: 0
buildFromReviewBlocksSelectedUnapprovedNoteInSemanticMode failed because the report did not contain missing-approved-snapshot.
buildFromReviewIgnoresFreshGeneratedCandidateWhenApprovedExists failed because write mode still looked for review/blog/essay/en.md.
```

Report tests added before report implementation:

```text
mvn -q -Dtest=ReportBuilderTest#writeReportListsIgnoredCandidatesSeparatelyFromDiagnostics+blockedWriteReportIncludesApprovedReleaseDiagnosticCodeVocabulary test
```

Initial result:

```text
Compilation failure: ReportBuilder.buildWriteReport did not yet accept ignored drafts.
```

Green verification after implementation:

```text
mvn -q -Dtest=AstroExportCommandTest#inspectBridgeHasExactSchemaAndIsReadOnlyWithWorkspaceHealth+buildFromReviewBlocksSelectedUnapprovedNoteInSemanticMode+buildFromReviewIgnoresFreshGeneratedCandidateWhenApprovedExists test
```

Result: passed.

## Tests Run

```text
mvn -q -Dtest=AstroExportCommandTest,ReportBuilderTest,NativeCliParityTest test
```

Result: passed. Output contained only JNA native-access warnings.

```text
mvn -q -Dtest=AstroExportCommandTest,ReportBuilderTest,NativeCliParityTest,ReviewLaunchPlannerTest,SiteWriterTest test
```

Result: passed. Output contained only JNA native-access warnings.

```text
git diff --check
```

Result: passed.

## Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/BridgeResponse.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/fs/SiteWriter.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/references/VaultReferenceCatalog.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/report/ReportBuilder.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/report/ReportBuilderTest.java`
- `.superpowers/sdd/2026-07-30-late-bound-semantic-links/task-7-report.md`

## Self-Review Findings

- Fixed an initial weak semantic candidate-state derivation that only inspected `translationStatus`; it now reuses the existing candidate English review validation after validating the semantic triple sidecar.
- Verified the writer guard is not a post-commit-only check: it runs after the Astro gate, before live movement, at forward boundaries, and after final install validation.
- Confirmed the legacy path remains active when the semantic activation marker is absent.
- Confirmed unrelated untracked context files were not staged.

## Concerns

- `approvalImpactSummary` is best-effort after the approved snapshot commit. If rebuilding the approved release index fails after a successful approval, the approval response omits the summary instead of failing a completed approval.
- The diagnostic-code vocabulary is surfaced in reports, but the underlying older exception codes such as `invalid-approved-snapshot` are not renamed in this task unless the approved release path emits one of the design codes directly.
