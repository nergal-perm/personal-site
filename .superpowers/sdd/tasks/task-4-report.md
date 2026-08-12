# Task 4 report — concepts/concept end-to-end slice

## Status

DONE_WITH_CONCERNS

Task 4 is implemented and verified on `master`. The S17d task checklist now marks Tasks 1–4 complete, based on the current source and passing verification.

## Changes

- Added `ConceptAcceptanceTest.conceptCompletesAdmissionThroughSiteInstallation()`.
  - Uses the real `NoteIntake`, `PrepareHandler`, `MarkReviewedHandler`, `BuildFromReviewHandler`, and `FilesystemManagedSiteInstaller` path with in-memory vault, candidate, approved, and release stores.
  - Covers a Russian concept with populated `notThis`, two ordered relation objects, and two ordered examples.
  - Supplies distinct English text for every translated field through `TranslationWorker.createNull(...)`.
  - Verifies both locale files preserve the declared YAML shape, source order, and locale-specific values.
- Extended `WritePublicationContractCliAcceptanceTest`.
  - Verifies the sorted `concepts/concept` contract entry.
  - Verifies optional `notThis` as a non-blank string, `examples` as a non-blank string list, and `relations` as a structured list with `name` and `relation` members.
- Updated `openspec/changes/s17d-concepts-concept-kind/tasks.md` checkboxes for Tasks 1.1–4.3.

## Verification

Focused command:

```text
mvn -f publication-exporter/pom.xml test -Dtest=ConceptPublicationKindTest,FieldContractTest,PublicationContractConformanceTest,FilesystemManagedSiteInstallerTest,ConceptAcceptanceTest,WritePublicationContractCliAcceptanceTest
```

Result: 93 tests passed, 0 failures, 0 errors.

Full command:

```text
mvn -f publication-exporter/pom.xml test
```

Result: 724 tests passed, 0 failures, 0 errors.

`git diff --check` passed.

## Concern

The required `graphify update .` refresh was attempted after the test change, but the environment rejected the graph-output write with `Operation not permitted`. This does not affect source compilation or Maven verification; graphify metadata may need a writable environment refresh separately.

The first focused run exposed only a test-fixture newline assumption for the English null-translation body. The assertion was corrected to match the intentionally supplied body bytes, and the focused rerun passed.

## Delivery

No unrelated untracked Haft notes or problem records were included. The implementation is ready for the requested commit; the commit hash is supplied in the final handoff.
