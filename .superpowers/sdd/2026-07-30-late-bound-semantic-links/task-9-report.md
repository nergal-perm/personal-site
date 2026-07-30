# Task 9 Report: Journaled All-or-Nothing Semantic Cutover

Status: DONE_WITH_CONCERNS

## Implemented

- Added `SemanticMigrationService` with:
  - `apply` mode under the existing exclusive semantic operation lock.
  - journal path `<review>/.semantic-links/migration-v1.journal.json`.
  - activation marker path `<review>/.semantic-links/schema-v1.active.json`.
  - private staging and recovery roots under `<review>/.semantic-links/`.
  - journaled page states: `planned`, `staged`, `installed`, `verified`, `cleanup-pending`, `complete`.
  - atomic published-directory exchange using the existing `AtomicExchange`/`JnaAtomicExchange` path.
  - rollback from displaced/staged legacy bytes.
  - roll-forward from recorded staged bytes after hash validation.
  - failure-injection hooks for the requested cutover boundaries.
- Strengthened `SemanticSchemaState` journal validation:
  - complete journal must be schema v1.
  - marker and journal inventory/catalog hashes must match.
  - complete journal must contain recovery root and complete per-page evidence.
  - malformed or incomplete marker/journal combinations return `MIGRATION_INCOMPLETE`.
- Wired CLI modes for `migrate-semantic-links`:
  - read-only inventory mode remains the default.
  - `--apply --decisions <path>`.
  - `--roll-forward`.
  - `--roll-back`.
  - rejects conflicting mutation modes.
  - mutation failures include journal and recovery paths in diagnostics.
- Added `CommandServices` semantic migration service adapter methods.
- Updated `ApprovedSnapshotRepository` migration-incomplete diagnostics to include journal and recovery paths.
- Updated repository test activation fixtures for the stricter complete journal shape.

## TDD Evidence

Initial RED command:

```bash
mvn -q -Dtest=SemanticSchemaStateTest,SemanticMigrationServiceTest test
```

Expected red result observed: test compilation failed because `SemanticMigrationService` and the new journal-aware service API did not exist.

Green/fix commands:

```bash
mvn -q -Dtest=SemanticSchemaStateTest,SemanticMigrationServiceTest test
```

Result: passed after implementing service, rollback, roll-forward, and schema-state journal validation.

```bash
mvn -q -Dtest=SemanticSchemaStateTest,SemanticMigrationServiceTest,AstroExportCommandTest test
```

Result: passed after CLI wiring.

Final required verification:

```bash
mvn -q -Dtest=SemanticSchemaStateTest,SemanticMigrationServiceTest,ApprovedSnapshotRepositoryTest,AstroExportCommandTest,NativeCliParityTest test
```

Result: passed. Output included only JNA native-access warnings from test JVM startup.

Whitespace verification:

```bash
git diff --check
```

Result: passed with no output.

## Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticSchemaState.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticMigrationService.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/review/ApprovedSnapshotRepository.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticSchemaStateTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticMigrationServiceTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/ApprovedSnapshotRepositoryTest.java`
- `.superpowers/sdd/2026-07-30-late-bound-semantic-links/task-9-report.md`

## Self-Review Findings

- Fixed: `apply` initially rejected all inventories with decision-required pages even after validating decisions. It now requires decision coverage for non-automatic pages.
- Fixed: rollback initially mishandled an `installed` page whose staged path held displaced legacy bytes. It now exchanges that staged legacy directory back into `published`.
- Fixed: the first install-boundary hook moved earlier enough that a failure after page install leaves journal state `installed`, matching the requested failure evidence.
- Fixed: older repository tests used a shallow complete journal fixture; they now write full schema-v1 complete evidence.

## Concerns

- `SemanticMigrationService` validates decision coverage, but corrected-order decisions are not yet used to replace the installed English bytes with the external corrected file. That path is covered by validation, not by byte installation.
- The implementation provides failure hooks for parity projection and Astro gate boundaries, but the service does not yet run a full materialized-release parity comparison or a real Astro content gate against a staged output tree. Existing selected tests pass, but this is narrower than the brief's strongest wording.
- Cleanup failure after marker installation is represented by `cleanup-pending` page state in the journal, but the current CLI success payload does not expose the exact cleanup recovery paths beyond the journal itself.

## Fix Round 1

Status: DONE

### Changed

- Hardened page installation journaling by writing and forcing `installing` before each atomic exchange, then teaching roll-forward and rollback to recover the `installing` state without deleting the displaced legacy bytes.
- Rejected `--apply` when an existing journal leaves schema state `MIGRATION_INCOMPLETE`, so a new apply cannot erase staged/recovery evidence that must be rolled forward or rolled back first.
- Staged the semantic catalog, recorded catalog published/staged/displaced journal paths, installed it atomically before activation, validated its hash during staged validation, roll-forward, and installed verification, and added catalog evidence to complete-journal schema validation.
- Applied approved corrected-order decisions to staged English semantic bytes and derived reference order/IDs from the reconciled materialization.
- Replaced the no-op Astro gate path with a gate call against the staging root; CLI apply now passes the real Astro gate, and the CLI test verifies the staged content directory is used.
- Made cleanup failure nonblocking for schema activation by accepting `cleanup-pending` page entries in an otherwise complete journal.

### Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticMigrationService.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticSchemaState.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticMigrationServiceTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticSchemaStateTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/review/ApprovedSnapshotRepositoryTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
- `.superpowers/sdd/2026-07-30-late-bound-semantic-links/task-9-report.md`

### Tests Run

```bash
mvn -q -Dtest=SemanticSchemaStateTest,SemanticMigrationServiceTest test
```

Output:

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Result: passed.

```bash
mvn -q -Dtest=SemanticSchemaStateTest,SemanticMigrationServiceTest,ApprovedSnapshotRepositoryTest,AstroExportCommandTest,NativeCliParityTest test
```

Output:

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Result: passed.

```bash
git diff --check
```

Output: no output.

Result: passed.

### Concerns

- No remaining concerns for the listed Critical/Important fix-round findings.

## Fix Round 2

Status: DONE

### Changed

- Added real staged materialized-release parity validation in `SemanticMigrationService`.
- The service now loads staged semantic snapshots through `ApprovedSnapshotRepository`, materializes them with `ApprovedReleaseMaterializer`, and compares the resulting RU/EN public release entries against the legacy approved projection before the `PARITY_PROJECTED` boundary.
- Corrected-order decisions are treated as the explicit approved exception for English body parity, using the corrected English file as the expected projection.
- Parity validation now uses the durable journal on disk for staged page hashes, preserving the journal-as-evidence recovery model.
- Kept the existing real Astro gate against `.semantic-links/staging-v1`.
- Added a regression test where a hash-valid staged sidecar retargets a semantic reference to the wrong approved page; apply now rejects it before activation with a parity diagnostic.
- Updated CLI apply fixture data so the parity comparison has a complete approved target set while read-only bridge tests remain scoped to inventory behavior.

### Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticMigrationService.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticMigrationServiceTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
- `.superpowers/sdd/2026-07-30-late-bound-semantic-links/task-9-report.md`

### Tests Run

```bash
mvn -q -Dtest=SemanticMigrationServiceTest#stagedMaterializedReleaseMustMatchLegacyProjection test
```

Initial RED result: failed before the production fix because the service did not reject the hash-valid staged retarget as a parity violation.

Final output:

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Result: passed.

```bash
mvn -q -Dtest=SemanticSchemaStateTest,SemanticMigrationServiceTest test
```

Output:

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Result: passed.

```bash
mvn -q -Dtest=SemanticSchemaStateTest,SemanticMigrationServiceTest,ApprovedSnapshotRepositoryTest,AstroExportCommandTest,NativeCliParityTest test
```

Output:

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Result: passed.

```bash
git diff --check
```

Output: no output.

Result: passed.

### Concerns

- No remaining concerns for the fix-round 2 parity finding.

## Fix Round 3

Status: DONE

### Changed

- Fixed editorial expected release target reconstruction in `SemanticMigrationService` parity validation.
- Expected editorial RU/EN entries now use `src/data/pages/<language>/<publicId>.json`, matching `ReviewWorkspace` and staged semantic materialization.
- Added focused editorial parity regression coverage for a valid `editorial/home` migration.
- Preserved the staged semantic materialization comparison, Astro staging gate, and prior recovery behavior.

### Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticMigrationService.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticMigrationServiceTest.java`
- `.superpowers/sdd/2026-07-30-late-bound-semantic-links/task-9-report.md`

### Tests Run

```bash
mvn -q -Dtest=SemanticMigrationServiceTest#editorialMigrationPassesMaterializedReleaseParity test
```

Initial RED result: failed before the production fix with `targetPath expected src/content/editorial/ru/home.md but was src/data/pages/ru/home.json`.

Final output:

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Result: passed.

```bash
mvn -q -Dtest=SemanticSchemaStateTest,SemanticMigrationServiceTest test
```

Output:

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Result: passed.

```bash
mvn -q -Dtest=SemanticSchemaStateTest,SemanticMigrationServiceTest,ApprovedSnapshotRepositoryTest,AstroExportCommandTest,NativeCliParityTest test
```

Output:

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Result: passed.

```bash
git diff --check
```

Output: no output.

Result: passed.

### Concerns

- No remaining concerns for the fix-round 3 editorial parity finding.
