# Task 4 report: Make Apply Consume Decisions Safely

## Result

Task 4 is implemented in the authorized `/Users/eugene/Dev/personal-site` master checkout. Apply now turns the validated typed decision set into an explicit executable decision plan. Corrected-page decisions continue to provide the complete corrected RU/EN snapshots used for ambiguous pages. Legacy rendering fails closed with an actionable error if an occurrence has no proposed English destination instead of throwing a null dereference or producing an unsafe partial migration.

The test fixture contains an ambiguous occurrence whose live inventory has both `proposedEnSpan: null` and `proposedEnDestination: null`. Apply fails when that page has no decision and succeeds with the page-corrected decision. The successful path validates the installed `references.json` against both installed snapshots and materializes public output with no `ref:` or `vault-ref-*` tokens.

## Exact files changed

- `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationInventory.java`
  - Added `DecisionSet.executable()` and immutable `ExecutableDecisions` to expose validated corrected-order and corrected-page decisions in the shape consumed by apply planning.
- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticMigrationService.java`
  - `stagePlan(...)` now consumes the executable decision plan.
  - Renamed the legacy renderer to `buildMigrated(...)` and added a fail-closed guard for null proposed English destinations.
  - Existing exact-page automatic migration, corrected RU/EN snapshot installation, sidecar validation, journal, staging, recovery, roll-forward, rollback, parity, and activation ordering remain unchanged.
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticMigrationServiceTest.java`
  - Added null-proposal assertions for the ambiguous fixture.
  - Added apply-without-decision rejection coverage.
  - Added corrected-page sidecar validation and public materialization token checks.
- `.superpowers/sdd/2026-08-01-semantic-link-decision-workflow-handoff/task-4-report.md`
  - This report.

## Tests and commands

Required service test:

```text
$ cd /Users/eugene/Dev/personal-site/exporter-java
$ mvn -q -Dtest=dev.eugene.astroexport.migration.SemanticMigrationServiceTest test
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native .../jna-5.19.0.jar
WARNING: Restricted methods will be blocked in a future release
```

Exit status: `0`; all `SemanticMigrationServiceTest` tests passed.

Focused inventory and service regression run:

```text
$ mvn -q -Dtest=dev.eugene.astroexport.migration.ReferenceMigrationInventoryTest,dev.eugene.astroexport.migration.SemanticMigrationServiceTest test
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native .../jna-5.19.0.jar
WARNING: Restricted methods will be blocked in the future
```

Exit status: `0`; both focused test classes passed.

Diff hygiene:

```text
$ git diff --check
<no output>
```

## Self-review

- Exact-page automatic pages still use the existing proposed route conversion and are not forced through corrected snapshots.
- Corrected-page decisions are selected by page key and supply both corrected language snapshots; no attempt was made to synthesize an ambiguous destination from absent proposal data.
- `PageReferenceMapCodec.validate(...)` is exercised after installation in the ambiguous corrected-page test.
- Public materialization is exercised through `ApprovedReleaseMaterializer`; Russian and English manifest bodies are asserted free of `ref:` and `vault-ref-*` tokens.
- Existing journal/staging/recovery code was not reordered or weakened.
- The real vault and real legacy review workspace were not accessed by the fixture or tests; all test paths are under JUnit temporary directories.
- Pre-existing untracked `exporter-java/reports/` and `exporter-java/review/` artifacts were preserved and excluded from staging.

## Concerns

- Maven emits the known JNA restricted-native-access warning on this runtime; it does not affect the passing exit status.
- The new `ExecutableDecisions` wrapper is intentionally small and currently carries only corrected-order and corrected-page decisions because those are the apply interfaces in the Task 2 contract. Span confirmations remain inventory validation data and do not invent destinations for ambiguous occurrences.
