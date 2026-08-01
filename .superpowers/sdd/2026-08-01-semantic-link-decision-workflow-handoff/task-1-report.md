# Task 1 Implementation Report: Characterize the Current Blocker

## Status

Implemented and verified. The change documents the current decision-validation blocker without changing production code.

## Files changed

- `exporter-java/src/test/java/dev/eugene/astroexport/migration/ReferenceMigrationInventoryTest.java`
  - Added `ambiguousTranslationWithNoProposedEnglishSpanCannotBeConfirmed`.
  - Added `ambiguousTranslationConfirmationRequiresEnglishSpan`.
  - Extended the local rejection assertion helper to verify validator messages where required.
- `.superpowers/sdd/2026-08-01-semantic-link-decision-workflow-handoff/task-1-report.md`
  - Added this implementation report.

Pre-existing untracked `exporter-java/reports/` and `exporter-java/review/` artifacts were preserved and not modified.

## Behavior documented

The focused fixture contains duplicate raw occurrences and duplicate approved English links, with a matching legacy order. Each occurrence is classified as `ambiguous-translation` and has `proposedEnSpan = null`.

- A decision containing `"decision":"confirm"` and arbitrary `enSpan` `{ "start": 0, "end": 1 }` is rejected with code `hash-mismatch` and message `confirmed English span does not match inventory`.
- A confirm decision without `enSpan` is rejected with code `missing-en-span` and message `confirm requires enSpan`.

## Tests

Exact required command:

```text
cd /Users/eugene/Dev/personal-site/exporter-java
mvn -q -Dtest=dev.eugene.astroexport.migration.ReferenceMigrationInventoryTest test
```

Final output:

```text
(no stdout/stderr; process exited with code 0)
```

Surefire result: `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`.

During fixture development, the first two runs failed because the duplicate-English fixture also had an empty legacy order sidecar, which intentionally produced page-level `order-mismatch`. The fixture was corrected to include the matching order `ref-0001`, `ref-0002`; no production behavior was changed.

Additional verification:

```text
cd /Users/eugene/Dev/personal-site
git diff --check
```

Output:

```text
(no output; process exited with code 0)
```

## Self-review

- Scope is limited to the requested migration-inventory test file plus this report.
- The tests exercise the existing public `inspect(...)` and `validateDecisions(...)` interfaces.
- The ambiguous occurrence explicitly asserts both classification and null proposed English span.
- The confirm decision uses the required arbitrary span and checks the required code/message.
- The missing-span test checks the required code/message.
- Existing tests and helper behavior were left intact apart from the additive message assertion overload.
- Pre-existing untracked reports and review artifacts remain unmodified.

## Concerns

None. The tests intentionally capture the current blocker; they do not claim that ambiguous decisions are currently confirmable.
