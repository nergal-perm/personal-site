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

## Fix round 1/5: Terra findings addressed

### Changes

- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticDecisionDraftWriter.java`
  - Now receives the review root and rejects every draft destination inside it before creating directories or files. This protects `review/*/published` and the rest of the legacy review workspace from overwrite.
  - Marks generated payloads with `draftOnly: true` and `draftStatus: needs-human-conversion`.
  - Generates executable page-corrected decisions only for `confirmed-needed` pages with safe approved RU/EN snapshots and non-blank `targetRef` on every occurrence. Unsafe, unresolved, and order-mismatch pages remain context-only.
- `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
  - Passes `--review` into the writer so the path safety boundary is enforced at the CLI boundary.
- `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationInventory.java`
  - Rejects `draftOnly` payloads with `draft-not-converted`; a human must convert the review scaffold before `--apply` can validate it.
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
  - Added a regression attempting to use `review/blog/page/published/ru.md` as the draft destination and proves the approved snapshot remains byte-identical.
  - Updated the deterministic draft fixture to use a `confirmed-needed` page.
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/ReferenceMigrationInventoryTest.java`
  - Proves an untouched generated draft is rejected as `draft-not-converted`.
  - Proves an unresolved page retains review context but has no executable decision.
- `.superpowers/sdd/2026-08-01-semantic-link-decision-workflow-handoff/task-3-report.md`
  - Appended this fix-round report.

### Exact verification

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest=dev.eugene.astroexport.cli.AstroExportCommandTest test
```

Result: exit code 0; 50 tests, 0 failures, 0 errors, 0 skipped. Maven emitted the existing JNA restricted-native-access warning.

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest=dev.eugene.astroexport.migration.ReferenceMigrationInventoryTest test
```

Result: exit code 0; 18 tests, 0 failures, 0 errors, 0 skipped.

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -DskipTests compile
```

Result: exit code 0.

```bash
cd /Users/eugene/Dev/personal-site
git diff --check
```

Result: passed with no output.

### Fix-round self-review and concerns

- A generated draft is now visibly and executably non-final: `draftOnly` is rejected by the same live validator used by apply. Removing that marker is the explicit human conversion boundary.
- Draft path validation runs before draft directory creation and rejects any path under the supplied review root, including approval-owned `published` files.
- The executable decision gate is fail-closed for unsafe, unresolved, and order-mismatch pages, and independently rejects null/blank target references.
- The writer still creates mechanical semantic-link scaffolds for safe `confirmed-needed` pages. Those links are review aids, not assertions of intent; conversion requires human editing and removal of `draftOnly`.
- Pre-existing untracked `exporter-java/reports/` and `exporter-java/review/` artifacts remain untouched. No real vault or real legacy review workspace was used.

## Fix round 2/5: symlinked draft destination protection

### Changes

- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticDecisionDraftWriter.java`
  - Resolves the review root and the nearest existing destination ancestor with `toRealPath()` before creating any draft directory or file.
  - Reconstructs the intended destination through unresolved path components and rejects destinations whose real path is inside the real review root.
  - Retains the lexical check, `draftOnly` boundary, and safe `confirmed-needed`/non-blank-target gating from fix round 1.
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
  - Added a regression where an outside draft path is a symlink into `review/blog/page/published`; it proves the approved RU snapshot remains byte-identical and no draft write occurs.
- `.superpowers/sdd/2026-08-01-semantic-link-decision-workflow-handoff/task-3-report.md`
  - Appended this fix-round report.

### Exact verification

Focused draft regression:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q '-Dtest=dev.eugene.astroexport.cli.AstroExportCommandTest#*Draft*' test
```

Result: exit code 0; 2 tests, 0 failures, 0 errors, 0 skipped.

Required CLI suite:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest=dev.eugene.astroexport.cli.AstroExportCommandTest test
```

Result: exit code 0; 51 tests, 0 failures, 0 errors, 0 skipped. Maven emitted the existing JNA restricted-native-access warning.

Fixture verification:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest=dev.eugene.astroexport.migration.ReferenceMigrationInventoryTest test
```

Result: exit code 0; 18 tests, 0 failures, 0 errors, 0 skipped.

Compile check:

```bash
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -DskipTests compile
```

Result: exit code 0.

Diff check:

```bash
cd /Users/eugene/Dev/personal-site
git diff --check
```

Result: passed with no output.

### Fix-round self-review and concerns

- The safety check now fails closed if the review root or an existing destination ancestor cannot be resolved, and it runs before `Files.createDirectories` and before every atomic write.
- Symlinked directories and existing symlinked destination files are resolved through the nearest existing ancestor, so lexical escapes cannot route writes into approval-owned snapshots.
- The `draftOnly` boundary and unsafe/unresolved/order-mismatch gating from fix round 1 remain unchanged.
- Concern: draft output still requires a human conversion step; only converted payloads with `draftOnly` removed can enter the existing decision validator.
- Pre-existing untracked `exporter-java/reports/` and `exporter-java/review/` artifacts remain untouched. No real vault or real legacy review workspace was used.
