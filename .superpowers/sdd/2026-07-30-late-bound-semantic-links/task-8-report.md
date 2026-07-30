# Task 8 Report: Read-Only Legacy Migration Inventory and Aggregate Decisions

## Status

DONE_WITH_CONCERNS

## Implemented

- Added `ReferenceMigrationAligner` for read-only legacy wikilink alignment against approved RU/EN Markdown.
- Added `ReferenceMigrationInventory.inspect(Path vault, Path review)` and overload with report path.
- Added deterministic inventory JSON with `inventorySha256`, page summaries, occurrence contexts, classifications, and proposed semantic triples for exact occurrences.
- Added conservative unsafe handling for symlink, hard-link, invalid UTF-8, incomplete/unsafe approved leaves, unresolved targets, ambiguous English spans, order mismatch, and current-source drift.
- Added aggregate decision validation for schema version 1, inventory hash freshness, unknown keys, unsupported decisions, confirm span validation, corrected English path containment, corrected English hash validation, and corrected-order completeness.
- Added Picocli command:
  - `migrate-semantic-links --vault --review --astro --report --json`
- Wired bridge summary counts for exact, confirmed-needed, unresolved, order-mismatch, unsafe, and occurrences.
- Updated GraalVM reachability metadata and native parity expectations for the new command.

## TDD Evidence

Red command:

```bash
mvn -q -Dtest=ReferenceMigrationAlignerTest,ReferenceMigrationInventoryTest,AstroExportCommandTest test
```

Observed red state:

- Compilation failed because `ReferenceMigrationAligner` and `ReferenceMigrationInventory` did not exist.
- After fixing a test fixture construction issue, the red state was cleanly missing Task 8 production types.

Green focused command:

```bash
mvn -q -Dtest=ReferenceMigrationAlignerTest,ReferenceMigrationInventoryTest,AstroExportCommandTest,NativeCliParityTest test
```

Observed result:

- Exit 0.
- Only JNA native-access warnings were printed.

Full regression command:

```bash
mvn -q test
```

Observed result:

- Exit 0.
- Only JNA native-access warnings and the existing help output from the smoke test were printed.

## Files Changed

- `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationAligner.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationInventory.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/CommandServices.java`
- `exporter-java/src/main/resources/META-INF/native-image/dev.eugene/astro-export/reachability-metadata.json`
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/ReferenceMigrationAlignerTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/ReferenceMigrationInventoryTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/nativeimage/NativeCliParityTest.java`
- `.superpowers/sdd/2026-07-30-late-bound-semantic-links/task-8-report.md`

## Self-Review Findings

- `git diff --check` passed.
- The CLI path does not call release materialization, site writing, catalog writes, approved snapshot writes, candidate writes, workflow state updates, or semantic schema state transitions.
- Inventory report writing is limited to the explicit `--report` path.
- Tests include filesystem snapshots for vault/review/Astro read-only behavior and deterministic report bytes.

## Concerns

- The aligner is intentionally conservative and test-covered for the Task 8 cases, but it does not yet implement a broad paragraph-anchor dynamic-programming search for all possible prose translation layouts. It enumerates candidate spans and compares unique document-order assignments; unclear/complex cases fall into confirmation-required or unsafe classifications rather than automatic triples.
- Corrected-English decision validation is read-only and validates containment/hash/completeness, but it does not materialize or persist any corrected-order state; that remains Task 9.
