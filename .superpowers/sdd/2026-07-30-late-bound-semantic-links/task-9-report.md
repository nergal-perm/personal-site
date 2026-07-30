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
