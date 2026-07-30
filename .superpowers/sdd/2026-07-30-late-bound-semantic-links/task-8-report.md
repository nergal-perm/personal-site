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

## Fix Round 1/5

### Status

DONE_WITH_CONCERNS

### Findings Fixed

- Fixed ordering comparison to use unique occurrence signatures rather than `targetRef`, so two occurrences pointing at the same target cannot be reversed in English and still classified exact.
- Preserved loaded `references.json.order` and marks a page `order-mismatch` when sidecar order differs from proposed occurrence order.
- Fixed `approve-corrected-order` validation to parse English links and compare destination order against the RU/source occurrence order; unchanged reversed corrected files now fail with `order-mismatch`.
- Missing/current-source read failures now become `unsafe-input` with a synthetic unsafe occurrence instead of an automatic exact page.
- Raw source parsing now uses `FrontmatterDocument.parse(...).body()`, so frontmatter wikilinks are not inventoried as inline body references.
- English plain-text candidates are no longer accepted from visible-label equality alone; they require surrounding/rendered-context anchoring.
- `--astro` is now passed through CLI services into inventory. Inventory scans Astro Markdown frontmatter `pageRef` and `route` fields and uses those current routes before vault-path fallback routes.

### Covering Tests Added

- `ReferenceMigrationAlignerTest.sameTargetDifferentLabelsReversedInEnglishIsOrderMismatch`
- `ReferenceMigrationAlignerTest.doesNotInferEnglishOccurrenceFromPlainTranslatedLabelOnly`
- `ReferenceMigrationInventoryTest.sidecarOrderMismatchPreventsExactAutomaticInventory`
- `ReferenceMigrationInventoryTest.missingCurrentSourceIsUnsafeInput`
- `ReferenceMigrationInventoryTest.rawFrontmatterLinksAreNotInventoried`
- `ReferenceMigrationInventoryTest.inventoryUsesCurrentAstroRoutesInsteadOfVaultPathFallbacks`
- `ReferenceMigrationInventoryTest.correctedEnglishDecisionRejectsUnchangedReversedOrder`

### Commands And Output

Red command:

```bash
mvn -q -Dtest=ReferenceMigrationAlignerTest,ReferenceMigrationInventoryTest test
```

Output:

```text
[ERROR] COMPILATION ERROR :
[ERROR] /Users/eugene/Dev/personal-site/exporter-java/src/test/java/dev/eugene/astroexport/migration/ReferenceMigrationInventoryTest.java:[167,42] no suitable method found for inspect(java.nio.file.Path,java.nio.file.Path,java.nio.file.Path,java.nio.file.Path)
...
```

Focused covering command:

```bash
mvn -q -Dtest=ReferenceMigrationAlignerTest,ReferenceMigrationInventoryTest,AstroExportCommandTest,NativeCliParityTest test
```

Output:

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Full regression command:

```bash
mvn -q test
```

First output:

```text
[ERROR] dev.eugene.astroexport.release.ApprovedReleaseMaterializerTest.publicOutputGateAllowsApprovedRoutesInsideVaultRoot ... ApprovedReleaseException: ru output contains private semantic payload
```

Follow-up isolation command:

```bash
mvn -q -Dtest=ApprovedReleaseMaterializerTest#publicOutputGateAllowsApprovedRoutesInsideVaultRoot test
```

Output:

```text
<empty output, exit 0>
```

Full regression rerun:

```bash
mvn -q test
```

Output:

```text
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
  migrate-semantic-links
  refresh-publication-queue
  write-publication-contract
```

Whitespace check:

```bash
git diff --check
```

Output:

```text
<empty output, exit 0>
```

### Concerns

- The first full-suite run exposed an order-sensitive `ApprovedReleaseMaterializerTest` failure outside the Task 8 files; the failing test passed in isolation and the full suite passed on rerun.
- English stripped/plain spans are still allowed only when anchored by rendered surrounding/full-context agreement. Unanchored English plain-label matches remain confirmation-required.

## Fix Round 2/5

### Status

DONE

### Findings Fixed

- Empty loaded `references.json.order` now participates in sidecar-order validation. A loaded empty order mismatches any nonempty proposed occurrence sequence.
- Added monotonic assignment enumeration for RU and EN candidates. Exact classification now depends on a unique monotonic assignment across the page rather than per-occurrence candidate cardinality.
- Duplicate Astro routes for the same `pageRef` and language are recorded as conflicts and passed into alignment. Any occurrence targeting a conflicting route page is classified `unsafe-input` instead of allowing arbitrary traversal-order overwrites.
- Updated CLI migration test fixtures to write complete sidecar order for single-reference exact pages under the stricter invariant.

### Covering Tests Added

- `ReferenceMigrationAlignerTest.monotonicAssignmentCanResolveExtraEnglishCandidates`
- `ReferenceMigrationInventoryTest.emptySidecarOrderWithProposedOccurrencesIsOrderMismatch`
- `ReferenceMigrationInventoryTest.duplicateAstroRoutesForSamePageRefAndLanguageAreUnsafe`

### Commands And Output

Red command:

```bash
mvn -q -Dtest=ReferenceMigrationAlignerTest,ReferenceMigrationInventoryTest test
```

Output:

```text
[ERROR] Tests run: 11, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 0.178 s <<< FAILURE! -- in dev.eugene.astroexport.migration.ReferenceMigrationInventoryTest
[ERROR] dev.eugene.astroexport.migration.ReferenceMigrationInventoryTest.emptySidecarOrderWithProposedOccurrencesIsOrderMismatch -- Time elapsed: 0.006 s <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <order-mismatch> but was: <exact>
...
[ERROR] dev.eugene.astroexport.migration.ReferenceMigrationInventoryTest.duplicateAstroRoutesForSamePageRefAndLanguageAreUnsafe -- Time elapsed: 0.009 s <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <unsafe> but was: <confirmed-needed>
...
[ERROR] Tests run: 12, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.011 s <<< FAILURE! -- in dev.eugene.astroexport.migration.ReferenceMigrationAlignerTest
[ERROR] dev.eugene.astroexport.migration.ReferenceMigrationAlignerTest.monotonicAssignmentCanResolveExtraEnglishCandidates -- Time elapsed: 0.001 s <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <EXACT_PAGE> but was: <CONFIRMED_NEEDED>
```

Focused covering command:

```bash
mvn -q -Dtest=ReferenceMigrationAlignerTest,ReferenceMigrationInventoryTest,AstroExportCommandTest,NativeCliParityTest test
```

Output:

```text
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.jna.Native in an unnamed module (file:/Users/eugene/.m2/repository/net/java/dev/jna/jna/5.19.0/jna-5.19.0.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

Full regression command:

```bash
mvn -q test
```

Output:

```text
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
  migrate-semantic-links
  refresh-publication-queue
  write-publication-contract
```

Whitespace command:

```bash
git diff --check
```

Output:

```text
<empty output, exit 0>
```

### Concerns

- No remaining concerns specific to this fix round.
