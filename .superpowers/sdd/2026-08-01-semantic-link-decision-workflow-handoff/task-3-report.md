# Task 3 Implementation Report: Generate a Human-Reviewable Decision Draft

## Status

Implemented and verified. Inventory mode now accepts `--draft <decisions.json>` and writes a deterministic page-corrected draft without applying migration. The draft contains per-occurrence review context and a page-corrected decisions payload bound to approved/corrected RU/EN snapshot hashes.

## Files changed

- `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
  - Added the read-only `--draft` inventory option.
  - Rejects combining `--draft` with apply or recovery modes.
  - Emits `draft-written` after inventory and draft generation.
- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticDecisionDraftWriter.java`
  - Added deterministic JSON draft generation.
  - Writes per-page `corrected-ru.md` and `corrected-en.md` files under the draft directory.
  - Includes page path/status, occurrence key, raw wikilink, targetRef, heading, reason, source context, RU/EN context, proposed span, and page-corrected paths/hashes.
  - Seeds corrected snapshots with ordered `ref:<occurrence-id>` links so a human can edit and validate the resulting page decision.
  - Writes only the requested draft path and its sibling draft files; it never writes `review/*/published`.
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
  - Added CLI fixture coverage for deterministic output and unchanged vault/review inputs.
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/ReferenceMigrationInventoryTest.java`
  - Added writer/validator fixture coverage for review fields, page-corrected decision shape, hashes, and unchanged approved snapshots.
- `.superpowers/sdd/2026-08-01-semantic-link-decision-workflow-handoff/task-3-report.md`
  - This report.

Pre-existing untracked `exporter-java/reports/` and `exporter-java/review/` artifacts were preserved and remain untracked. No real vault or real legacy review workspace was used.

## Tests and commands

Compile check:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -DskipTests compile
```

Result: passed with exit code 0.

Required CLI test:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest=dev.eugene.astroexport.cli.AstroExportCommandTest test
```

Result: passed with exit code 0; 49 tests, 0 failures, 0 errors, 0 skipped. Maven emitted the existing JNA restricted-native-access warning.

Fixture verification:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest=dev.eugene.astroexport.migration.ReferenceMigrationInventoryTest test
```

Result: passed with exit code 0; 17 tests, 0 failures, 0 errors, 0 skipped.

Additional check:

```bash
cd /Users/eugene/Dev/personal-site
git diff --check
```

Result: passed with no output.

## Self-review

- Draft mode is explicitly read-only with respect to migration state and rejects apply/recovery combinations.
- Approved snapshots are read through the existing inventory interface and are never rewritten.
- Page-corrected draft decisions use Task 2’s exact `approve-corrected-page` contract, including approved and corrected RU/EN hashes and relative paths.
- Draft output order follows inventory page and occurrence order; repeated generation produces identical JSON and corrected snapshot bytes.
- The fixture validates the generated decision through `validateDecisions`, proving that the generated hashes and ordered semantic-reference coverage are accepted by the live validator.
- The pre-existing untracked exporter artifacts are untouched.

## Concerns

- The initial corrected files are review scaffolds: they replace the first ordered Markdown links with semantic reference links and append placeholders when a page has fewer Markdown links than occurrences. A human must inspect/edit them before applying migration.
- Unsafe pages do not receive an executable page decision entry; their inventory context remains available in the draft for manual investigation.
