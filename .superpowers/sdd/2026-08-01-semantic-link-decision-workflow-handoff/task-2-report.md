# Task 2 Implementation Report: Choose and Implement the Decision Contract

## Status

Implemented and verified. The selected contract is page-corrected decisions for human-reviewed ambiguous/order cases. A validated decision carries its key, corrected-English path, approved-English snapshot hash, corrected-English hash, and an immutable copy of the validated corrected bytes. Existing exact span confirmations remain supported as a typed `SpanConfirmDecision`.

## Files changed

- `exporter-java/src/main/java/dev/eugene/astroexport/migration/ReferenceMigrationInventory.java`
  - Changed `DecisionSet` from accepted key strings to typed `Decision` payloads.
  - Added `SpanConfirmDecision` and `PageCorrectedDecision` with defensive byte copies.
  - Preserved schema, stale-inventory, unknown-key, malformed-decision, path-boundary, UTF-8, corrected-byte hash, span, and order validation.
  - Added required `approvedEnglishSha256` validation for page-corrected decisions.
- `exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticMigrationService.java`
  - Removed the second raw decision-file parse.
  - Apply and parity validation now consume the validated typed page-corrected payload and its captured bytes.
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/ReferenceMigrationInventoryTest.java`
  - Updated corrected-order fixtures for the approved-English snapshot hash.
  - Asserted typed payload fields and rejection after corrected bytes change.
- `exporter-java/src/test/java/dev/eugene/astroexport/migration/SemanticMigrationServiceTest.java`
  - Updated the apply fixture with the selected approved-English snapshot hash.
- `.superpowers/sdd/2026-08-01-semantic-link-decision-workflow-handoff/task-2-report.md`
  - Added this report.

Pre-existing untracked `exporter-java/reports/` and `exporter-java/review/` artifacts were preserved and not modified. No real vault or real legacy review workspace was used.

## Contract decision

Page-corrected is the safer contract for the blocker exposed by Task 1: ambiguous occurrences do not have a trustworthy English span, while a human can review and provide a corrected English snapshot. The decision binds that snapshot to both the fresh `inventorySha256` and the approved English snapshot hash, validates the corrected bytes before acceptance, and passes the captured bytes into apply. The narrow span-confirm type remains available for occurrences whose inventory already supplies an exact proposed span.

## Tests and commands

Compile check:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -DskipTests compile
```

Result: passed with exit code 0.

Required migration suite:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest='dev.eugene.astroexport.migration.*Test' test
```

Result: passed with exit code 0; 61 tests, 0 failures, 0 errors, 0 skipped. Maven emitted only the existing JNA restricted-native-access warning.

Diff check:

```text
cd /Users/eugene/Dev/personal-site
git diff --check
```

Result: passed with no output.

## Self-review

- Fresh inventory hash and unknown-key protections remain in the existing validation path.
- Malformed, unsupported, missing-span, stale-inventory, changed-corrected-bytes, invalid-UTF-8, path-escape, and order-mismatch cases remain rejected.
- The page-corrected decision now requires and checks `approvedEnglishSha256`, preventing approval against a different approved English snapshot.
- Apply no longer rereads decision JSON after validation, so it cannot silently use bytes different from those reviewed and hashed.
- Mutable byte arrays are defensively copied at construction and access boundaries.
- Existing Task 1 blocker regression tests remain intact.
- Only the four requested source/test files and this report are changed; unrelated untracked artifacts remain untracked.

## Concerns

Decision producers must add the new required `approvedEnglishSha256` field to every `approve-corrected-order` decision. No compatibility shim was added because accepting an unbound corrected snapshot would weaken the executable safety boundary.

## Fix round 1/5

Addressed both Terra findings.

- Added the page-level key `<pageRef>/page` and decision type `approve-corrected-page`.
- A page decision carries corrected Russian and English UTF-8 snapshots, four corrected/approved byte hashes, and defensive copies of both byte arrays.
- Page decisions cover every occurrence on a non-automatic page, including `confirmed-needed` pages whose occurrences have no proposed English destination. Coverage requires both corrected snapshots to contain the page occurrence IDs in inventory order as `ref:` links.
- Apply now consumes the validated page payload directly and stages the corrected RU/EN bytes; the ambiguous-page apply regression passes.
- Approved Russian and English files are re-read and hash-checked during decision validation, so mutation after inventory inspection is rejected even when the decision JSON remains otherwise valid.
- Existing occurrence-confirm and corrected-order compatibility paths remain intact.

### Fix-round tests

Focused page-contract validation:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest='dev.eugene.astroexport.migration.ReferenceMigrationInventoryTest' test
```

Result: passed with exit code 0; 16 tests, 0 failures, 0 errors, 0 skipped.

Focused end-to-end apply:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest='dev.eugene.astroexport.migration.SemanticMigrationServiceTest#correctedPageDecisionAppliesAmbiguousConfirmedNeededPage' test
```

Result: passed with exit code 0; 1 test, 0 failures, 0 errors, 0 skipped. Maven emitted only the existing JNA restricted-native-access warning.

Required migration suite:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest='dev.eugene.astroexport.migration.*Test' test
```

Result: passed with exit code 0; 63 tests, 0 failures, 0 errors, 0 skipped. Maven emitted only the existing JNA restricted-native-access warning.

Additional check:

```text
cd /Users/eugene/Dev/personal-site
git diff --check
```

Result: passed with no output.

### Fix-round self-review and concerns

- The real blocker is now executable: a duplicate/ambiguous confirmed-needed page is accepted by one page decision and successfully applied from corrected RU/EN semantic snapshots.
- The approved-English mutation regression changes the review `en.md` bytes after inventory creation while retaining a valid decision file; validation rejects it with `hash-mismatch`.
- Unknown keys, stale inventory hashes, path escape, UTF-8, corrected-byte hashes, and coverage checks remain fail-closed.
- No real vault or real legacy review workspace was touched; the new regressions use temporary fixtures.
- Concern: page-corrected decision producers must emit final semantic `ref:<occurrence-id>` links in both snapshots and include all six required hash/path fields. This is intentional: it makes human correction executable without guessing an ambiguous EN destination.
