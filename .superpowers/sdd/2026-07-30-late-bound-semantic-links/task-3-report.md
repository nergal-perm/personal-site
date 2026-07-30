# Task 3 Report: Semantic Preparation and Atomic Candidate Triples

## Status
DONE_WITH_CONCERNS

## Implemented
- Added semantic schema-state boundary:
  - `SemanticSchemaState.mode(Path reviewRoot)` returns `LEGACY`, `SEMANTIC`, or `MIGRATION_INCOMPLETE`.
  - Valid activation marker is `<review>/.semantic-links/schema-v1.active.json`.
  - Marker parsing is strict for `schemaVersion`, `inventorySha256`, `catalogSha256`, and `activatedAt`.
  - Any migration journal at `<review>/.semantic-links/migration-v1.journal.json` without a valid marker blocks as `MIGRATION_INCOMPLETE`.
- Added semantic operation locking:
  - `SemanticOperationLock.acquireShared(Path reviewRoot)`.
  - `SemanticOperationLock.acquireExclusive(Path reviewRoot)`.
  - Lock file is `<review>/.semantic-links/operations.lock`.
  - `JnaFileDescriptor` now supports nonblocking shared `flock` via `trySharedLock()`.
- Added semantic manifest preparation:
  - `ManifestResult.referencePlans()` keyed by `sourcePath`.
  - `ManifestBuilder.buildRussianManifest(SelectionResult, SemanticLinkContext)`.
  - `SemanticLinkContext(VaultReferenceCatalog, VaultReferenceResolver, Map<String, Optional<PageReferenceMap>>)`.
  - Non-editorial semantic mode uses `SemanticReferencePlanner` against the whole-vault resolver and computes hashes after semantic Markdown is produced.
  - Legacy `buildRussianManifest(SelectionResult)` remains source-compatible.
- Added atomic candidate triples:
  - `CandidateSnapshotStore.stage(Path pageDirectory, byte[] ru, byte[] en, byte[] references)`.
  - `CandidateSnapshotStore.PendingCandidate.commit(List<WorkflowStateService.SnapshotGuard>)`.
  - Installs `candidate/{ru.md,en.md,references.json}` as one directory swap.
- Wired semantic preparation:
  - Preparation acquires a shared semantic operation lease before reading schema mode and retains it through commit.
  - Semantic mode loads `VaultReferenceCatalog` from review root and builds semantic manifest/reference plans.
  - Translation prompt includes the exact semantic occurrence-ID invariant from the brief.
  - After EN validation, semantic mode binds `PageReferenceMap`, validates RU/EN/reference order, and installs the candidate triple atomically.
  - English occurrence order mismatches fail as `translation_failed` with `reference-order-mismatch`.
- Updated review readers and launch planning:
  - `ReviewWorkspace.readCandidateReferences(Path reviewRoot, String collection, String publicId)`.
  - `ReviewLaunchPlanner` prefers `candidate/ru.md` and `candidate/en.md` when `candidate/` exists, and validates `candidate/references.json` before returning a plan.

## Tests Added or Updated
- Added:
  - `CandidateSnapshotStoreTest`
  - `SemanticSchemaStateTest`
  - `SemanticOperationLockTest`
- Updated:
  - `ManifestBuilderTest`
  - `PrepareWorkflowTest`
  - `ReviewWorkspaceTest`
  - `ReviewLaunchPlannerTest`

## TDD Evidence
- Red command:
  - `mvn -q -Dtest=CandidateSnapshotStoreTest,SemanticSchemaStateTest,SemanticOperationLockTest,PrepareWorkflowTest,ManifestBuilderTest,ReviewWorkspaceTest,ReviewLaunchPlannerTest test`
- Red result:
  - Failed at test compilation because `SemanticLinkContext`, `SemanticOperationLock`, `SemanticSchemaState`, `CandidateSnapshotStore`, and `ReviewWorkspace.readCandidateReferences(...)` did not exist.
- Green command:
  - `mvn -q -Dtest=CandidateSnapshotStoreTest,SemanticSchemaStateTest,SemanticOperationLockTest,PrepareWorkflowTest,ManifestBuilderTest,ReviewWorkspaceTest,ReviewLaunchPlannerTest,TranslationProjectionTest test`
- Green result:
  - Passed.
  - JVM emitted JNA native-access warnings; no test failures.
- Additional check:
  - `git diff --check`
  - Passed.

## Files Changed
- `exporter-java/src/main/java/dev/eugene/astroexport/fs/JnaFileDescriptor.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/links/LinkProcessor.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/manifest/ManifestBuilder.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/model/ManifestResult.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/references/SemanticLinkContext.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/CandidateSnapshotStore.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewLaunchPlanner.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticSchemaState.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticOperationLock.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/manifest/ManifestBuilderTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/prepare/PrepareWorkflowTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/CandidateSnapshotStoreTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewLaunchPlannerTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/ReviewWorkspaceTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticSchemaStateTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticOperationLockTest.java`

## Self-Review Findings
- Candidate triple installation validates the sidecar before exposing `candidate/`, and `CandidateSnapshotStore` rechecks guards after visible commit.
- Semantic prepare still writes a legacy page-level `ru.md` while creating the semantic candidate triple. This preserves existing review workflow compatibility but is not a complete removal of legacy proposal leaves.
- `ReviewLaunchPlanner` treats `candidate/` presence as the switch for proposed files. It does not yet require semantic schema activation to reject legacy published two-file baselines; that stricter baseline behavior appears coupled to later approval/build migration tasks.

## Concerns
- I did not run the full Maven suite, only the focused suite requested by the brief plus `TranslationProjectionTest`.
- JNA emits native-access warnings under the current JDK. Tests pass, but future JDKs may require `--enable-native-access=ALL-UNNAMED`.

---

# Fix Round 1 Report

## Status
DONE_WITH_CONCERNS

## Findings Fixed
1. Approved reference maps are now loaded from `review/<collection>/<publicId>/published/references.json` in semantic preparation and supplied to `SemanticLinkContext`, so `SemanticReferencePlanner` can reuse approved occurrence IDs.
2. Activated semantic preparation no longer writes a top-level legacy `ru.md`; it renders the normalized RU review in memory and only exposes a validated `candidate/` triple. `ReviewLaunchPlanner` now rejects top-level proposal fallback while semantic mode is active and `candidate/` is absent.
3. `SemanticSchemaState` now validates a migration journal when present. A valid marker only enables `SEMANTIC` mode if the journal is complete and its `inventorySha256` and `catalogSha256` match the marker.
4. `CandidateSnapshotStore` now reports explicit recovery state. Rollback failure raises `CandidateSnapshotRecoveryException` with `CANDIDATE_VISIBLE` and recovery paths; staged cleanup failure raises `STAGED_CANDIDATE`; displaced cleanup failure returns recovery paths from `commit`.

## Covering Tests
- `PrepareWorkflowTest.semanticPrepareReusesApprovedOccurrenceIdsFromPublishedReferences`
- `PrepareWorkflowTest.semanticCandidateRejectsEnglishOccurrenceOrderMismatch`
- `ReviewLaunchPlannerTest.activatedSemanticModeRejectsLegacyProposalWhenCandidateTripleIsAbsent`
- `SemanticSchemaStateTest.validMarkerWithUnmatchedJournalBlocksAsIncomplete`
- `SemanticSchemaStateTest.validMarkerWithMatchingCompleteJournalEnablesSemanticMode`
- `CandidateSnapshotStoreTest.rollbackFailureReportsVisibleCandidateRecoveryPath`

## Commands and Output
- Red command:
  - `mvn -q -Dtest=CandidateSnapshotStoreTest,SemanticSchemaStateTest,PrepareWorkflowTest,ReviewLaunchPlannerTest test`
- Red output:
  - Failed at test compilation because `CandidateSnapshotStore.CandidateSnapshotRecoveryException` and `CandidateSnapshotStore.RecoveryDisposition` did not exist.
- Focused green command:
  - `mvn -q -Dtest=CandidateSnapshotStoreTest,SemanticSchemaStateTest,PrepareWorkflowTest,ReviewLaunchPlannerTest test`
- Focused green output:
  - Passed.
  - JVM emitted JNA native-access warnings.
- Full Task 3 covering command:
  - `mvn -q -Dtest=CandidateSnapshotStoreTest,SemanticSchemaStateTest,SemanticOperationLockTest,PrepareWorkflowTest,ManifestBuilderTest,ReviewWorkspaceTest,ReviewLaunchPlannerTest,TranslationProjectionTest test`
- Full Task 3 covering output:
  - Passed.
  - JVM emitted JNA native-access warnings.
- Whitespace command:
  - `git diff --check`
- Whitespace output:
  - Passed with no output.

## Concerns
- I did not run the full Maven suite, only the Task 3 covering suite plus focused fix suite.
- JNA native-access warnings remain under the current JDK.
