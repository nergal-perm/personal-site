# Task 5 Validation Report

## Status

Validated against the real vault, real legacy review workspace, and current site root without applying migration or recovery. The fresh inventory and human-reviewable draft were generated successfully. A non-mutating `--validate` CLI path was added because no validation-only command existed.

## Real inputs

- Vault: `/Users/eugene/Documents/personal-wiki/knowledge-base`
- Legacy review workspace: `/Users/eugene/Documents/personal-wiki/tools/astro-export/review`
- Astro site root: `/Users/eugene/Dev/personal-site/site`

## Exact commands and results

Build current Java classes:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -DskipTests compile
```

Result: exit code 0.

Read-only inventory, using the current Java classes because the existing native executable did not contain the already-implemented `--draft` option:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
/opt/homebrew/opt/openjdk/bin/java -cp "target/classes:$(cat target/classpath.txt)" dev.eugene.astroexport.AstroExportApp migrate-semantic-links \
  --vault /Users/eugene/Documents/personal-wiki/knowledge-base \
  --review /Users/eugene/Documents/personal-wiki/tools/astro-export/review \
  --astro /Users/eugene/Dev/personal-site/site \
  --report /Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-migration-inventory.json \
  --json
```

Result: exit code 1, status `decisions-required`; summary `exact=2`, `confirmedNeeded=20`, `unresolved=0`, `orderMismatch=0`, `unsafe=0`, `occurrences=140`.

Generate the draft:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
/opt/homebrew/opt/openjdk/bin/java -cp "target/classes:$(cat target/classpath.txt)" dev.eugene.astroexport.AstroExportApp migrate-semantic-links \
  --vault /Users/eugene/Documents/personal-wiki/knowledge-base \
  --review /Users/eugene/Documents/personal-wiki/tools/astro-export/review \
  --astro /Users/eugene/Dev/personal-site/site \
  --report /Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-migration-inventory.json \
  --draft /Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-decisions-draft.json \
  --json
```

Result: exit code 0, status `draft-written`; same summary. Draft path: `/Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-decisions-draft.json`. Draft SHA-256: `4d09d46a19acca9d5f6ee07226baa828024869a6f84a4a7fdf56d264b9b12078`. It has `draftOnly=true`, `draftStatus=needs-human-conversion`, 20 decisions, and 22 page records.

The untouched generated draft was checked through validation and rejected with `draft-not-converted`, proving the human-conversion boundary is active. A local conversion copy was then made by removing only `draftOnly` and `draftStatus`; it was not used for apply and does not modify the generated draft:

```text
jq 'del(.draftOnly, .draftStatus)' \
  reports/task-5-semantic-link-decisions-draft.json \
  > reports/task-5-semantic-link-decisions-converted-validation-copy.json

/opt/homebrew/opt/openjdk/bin/java -cp "target/classes:$(cat target/classpath.txt)" dev.eugene.astroexport.AstroExportApp migrate-semantic-links \
  --vault /Users/eugene/Documents/personal-wiki/knowledge-base \
  --review /Users/eugene/Documents/personal-wiki/tools/astro-export/review \
  --astro /Users/eugene/Dev/personal-site/site \
  --report /Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-migration-inventory.json \
  --validate \
  --decisions /Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-decisions-converted-validation-copy.json \
  --json
```

Result: exit code 0, status `validated`; same summary. Inventory report path: `/Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-migration-inventory.json`. Inventory SHA-256: `6322c6973e9a6d78cafc91c56c9ed84001bd38db4e4aa9bebd77d9a20fafd6a`; embedded `inventorySha256`: `82c1c5dceb9738ce30ada26475aa59c4fda7daef8132396d9fcb4dcc8120fcc3`. Validation-copy SHA-256: `99f37529f0164434b4ffb634a084b23dfb6cdb630cd4461c7dfd5402b1a7ffa7`.

Full JVM suite required by the brief:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q test
```

Result: exit code 0; 678 tests, 0 failures, 0 errors, 0 skipped. Maven emitted the existing JNA restricted-native-access warning. A CLI smoke test also printed normal usage text; it did not fail the suite.

Snapshot checks:

- Before and after manifests covered every `review/**/published/*` file plus semantic-link state metadata.
- All approval-owned published-file SHA-256 values were identical before and after.
- Semantic-link state metadata remained absent in both snapshots; no journal, recovery, catalog, activation marker, or lock artifact was created in the real review workspace.
- `git diff --check` passed.

## Self-review

- No `migrate-semantic-links --apply`, `--roll-forward`, or `--roll-back` command was run.
- Inventory and draft writes were limited to the explicitly generated files under `exporter-java/reports/`.
- The new `--validate` path performs fresh inventory plus `validateDecisions(...)` and has no staging, journal, recovery, or snapshot-writing behavior.
- Generated reports and the pre-existing untracked `exporter-java/reports/` and `exporter-java/review/` trees were not staged.

## Concerns

- The checked-out `target/astro-export` native executable was stale and could not be rebuilt because GraalVM/native-image is not installed in the active JDK. Validation used the current compiled Java classes directly; the native executable should be rebuilt in a GraalVM environment before distributing this new CLI option.
- The draft remains intentionally non-executable until human review removes its draft-only markers and confirms the page-corrected snapshots.

## Fix round 1/5

Addressed both open findings.

### Changes

- `exporter-java/src/main/java/dev/eugene/astroexport/cli/AstroExportCommand.java`
  - Rejects `--validate` combined with `--apply`, `--roll-forward`, or `--roll-back` before any inventory, apply, or recovery branch.
- `exporter-java/src/test/java/dev/eugene/astroexport/cli/AstroExportCommandTest.java`
  - Adds a CLI regression covering all three forbidden combinations.
  - Supplies enough arguments for the former `--validate --apply` path to reach apply, and asserts `metadata_blocked`, no inventory report, and unchanged review bytes.
- This report now records reproducible real-review snapshot manifest commands, fixed manifest paths, line counts, entry counts, and SHA-256 digests.

### Focused regression: exact commands and outputs

Red-before-fix command:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest='dev.eugene.astroexport.cli.AstroExportCommandTest#migrateSemanticLinksValidateCannotSelectMutationOrRecoveryMode' test
```

Result before the guard: exit code 1; assertion expected `metadata_blocked` but received `unsafe-input`, proving the test reached the formerly unsafe flow.

Green-after-fix command:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest='dev.eugene.astroexport.cli.AstroExportCommandTest#migrateSemanticLinksValidateCannotSelectMutationOrRecoveryMode' test
```

Result: exit code 0; 1 test, 0 failures, 0 errors, 0 skipped. Maven emitted the known JNA restricted-native-access warning.

The regression covers `--validate --apply`, `--validate --roll-forward`, and `--validate --roll-back`. Each returns `status=metadata_blocked`, contains `--validate cannot be combined`, leaves the review tree byte-identical, and creates no inventory report.

### Real validation rerun

Compile:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -DskipTests compile
```

Result: exit code 0.

Read-only inventory command:

```text
/opt/homebrew/opt/openjdk/bin/java -cp "target/classes:$(cat target/classpath.txt)" dev.eugene.astroexport.AstroExportApp migrate-semantic-links --vault /Users/eugene/Documents/personal-wiki/knowledge-base --review /Users/eugene/Documents/personal-wiki/tools/astro-export/review --astro /Users/eugene/Dev/personal-site/site --report /Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-migration-inventory.json --json
```

Output: exit code 1, `status=decisions-required`, summary `exact=2`, `confirmedNeeded=20`, `unresolved=0`, `orderMismatch=0`, `unsafe=0`, `occurrences=140`.

Draft command:

```text
/opt/homebrew/opt/openjdk/bin/java -cp "target/classes:$(cat target/classpath.txt)" dev.eugene.astroexport.AstroExportApp migrate-semantic-links --vault /Users/eugene/Documents/personal-wiki/knowledge-base --review /Users/eugene/Documents/personal-wiki/tools/astro-export/review --astro /Users/eugene/Dev/personal-site/site --report /Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-migration-inventory.json --draft /Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-decisions-draft.json --json
```

Output: exit code 0, `status=draft-written`, same summary.

Non-mutating validation command, using the existing conversion-copy procedure:

```text
jq 'del(.draftOnly, .draftStatus)' reports/task-5-semantic-link-decisions-draft.json > reports/task-5-semantic-link-decisions-converted-validation-copy.json
/opt/homebrew/opt/openjdk/bin/java -cp "target/classes:$(cat target/classpath.txt)" dev.eugene.astroexport.AstroExportApp migrate-semantic-links --vault /Users/eugene/Documents/personal-wiki/knowledge-base --review /Users/eugene/Documents/personal-wiki/tools/astro-export/review --astro /Users/eugene/Dev/personal-site/site --report /Users/eugene/Dev/personal-site/reports/task-5-semantic-link-migration-inventory.json --validate --decisions /Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-decisions-converted-validation-copy.json --json
```

Output: exit code 0, `status=validated`, same summary.

Artifact paths and SHA-256 values:

- Inventory: `/Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-migration-inventory.json`, `6322c6973e9a6d78cafc91c56c9ed84001bd38db4e4aa9bebd77d9a20fafd6a`.
- Draft: `/Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-decisions-draft.json`, `4d09d46a19acca9d5f6ee07226baa828024869a6f84a4a7fdf56d264b9b12078`.
- Converted validation copy: `/Users/eugene/Dev/personal-site/exporter-java/reports/task-5-semantic-link-decisions-converted-validation-copy.json`, `99f37529f0164434b4ffb634a084b23dfb6cdb630cd4461c7dfd5402b1a7ffa7`.

### Auditable real-review snapshot evidence

The following exact command was run before validation and again after the full suite. It writes only to `/private/tmp` and does not modify the real review workspace:

```text
review=/Users/eugene/Documents/personal-wiki/tools/astro-export/review
manifest=/private/tmp/task-5-fix-round-1-review-before.sha256 # after run used ...-after.sha256
{
  echo "manifest_version=1"
  echo "review_root=$review"
  echo "captured_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  find "$review" -type f -path '*/published/*' -print0 | sort -z | xargs -0 shasum -a 256
  for state_path in \
    "$review/.semantic-links/catalog-v1.json" \
    "$review/.semantic-links/schema-v1.active.json" \
    "$review/.semantic-links/migration-v1.journal.json" \
    "$review/.semantic-links/operations.lock"; do
    if test -e "$state_path"; then shasum -a 256 "$state_path"; else echo "MISSING $state_path"; fi
  done
} > "$manifest"
```

Concrete evidence:

- Before manifest: `/private/tmp/task-5-fix-round-1-review-before.sha256`, 51 lines, 44 published entries, SHA-256 `03ef3c0e8c8aad4380e26d74e7bf801b55f2e0943bcf51698d5d1345d7f238e`.
- After manifest: `/private/tmp/task-5-fix-round-1-review-after.sha256`, 51 lines, 44 published entries, SHA-256 `e4193c0ba75f2fa65a93c0f697f0d898ee6179c94ff54df91dd1c224b71bee50`.
- Comparing the manifests after excluding only line 3 (`captured_at`) reported `IDENTICAL_EXCEPT_CAPTURE_TIME`; every published-file hash and every semantic-state entry matched.
- The four semantic-state paths were `MISSING` in both manifests: catalog, schema activation marker, migration journal, and operations lock.

Full JVM suite:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q test
```

Result: exit code 0; 679 tests, 0 failures, 0 errors, 0 skipped. Output contained the known JNA restricted-native-access warning and normal CLI usage text from the smoke test.

### Fix-round self-review and concerns

- `--validate` is now exclusive with every mutating/recovery mode and the regression proves no inventory report or review mutation path is selected.
- No `--apply`, `--roll-forward`, or `--roll-back` command was run against the real review workspace.
- Approved snapshots remained hash-identical according to the two auditable manifests.
- The native limitation remains: `target/astro-export` is stale and GraalVM/native-image is unavailable in the active JDK; validation used current Java classes directly.
- An initial fix-round command typo generated equivalent local artifacts under `/Users/eugene/Dev/personal-site/reports/`; they were preserved as requested and are not staged.
