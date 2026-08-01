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
