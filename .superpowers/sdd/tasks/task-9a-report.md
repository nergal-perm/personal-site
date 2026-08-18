# Task 9a report: raw source-body freshness

## Implemented

- Added the non-optional `sourceBodyHash` value to `ReferenceMap`, including its Jackson accessor and participation in `equals()`, `hashCode()`, and `toString()`.
- Preserved both existing `ReferenceMap.of(...)` overloads. They default `sourceBodyHash` to the safe empty-string sentinel, so legacy/in-memory maps cannot silently pass raw-source freshness.
- Kept `sameContentAs()` unchanged; source freshness remains outside its five-field candidate-content identity contract.
- Extended `ReferenceMapCodec` to write `sourceBodyHash`, read it when present, and decode missing legacy data as `""`.
- Threaded the initially admitted raw `intake.body()` beside `sourceId` through `PrepareHandler.prepareNormalizedEssay(...)`, `prepareWithInstallLock(...)`, `prepareAdmittedEssay(...)`, and `prepareTranslatedEssay(...)` into `buildReferenceMap(...)`.
- `PrepareHandler.buildReferenceMap(...)` now stores `ContentHash.sha256Hex(sourceBody)` in the new field.
- Changed only `MarkReviewedHandler.sourceChangedDiagnostic(...)` to compare the current raw source-body hash with `referenceMap.sourceBodyHash()`. The other seven staleness diagnostics, including `candidateRuBodyChangedDiagnostic(...)`, were not changed.
- Added a real `PrepareHandler` to `MarkReviewedHandler` regression test for an unchanged source containing a wikilink, plus value-object and codec coverage for the new field and legacy sentinel.

## Exact new overload

```java
public static ReferenceMap of(
        PublicationIdentity identity,
        String sourceId,
        String ruHash,
        String enHash,
        String ruFieldsHash,
        String enFieldsHash,
        String structuredDataHash,
        List<Occurrence> occurrences,
        String sourceBodyHash)
```

## Test evidence

- TDD RED: `mvn -q -Dtest=ReferenceMapTest,ReferenceMapCodecTest,MarkReviewedHandlerTest test` exited 1 with the expected missing nine-argument `ReferenceMap.of(...)` and `sourceBodyHash()` compilation errors.
- Focused unit/integration set after implementation: the same command exited 0.
- Required acceptance: `cd publication-exporter && mvn -q -Dtest=LateBoundTargetActivationAcceptanceTest test` exited 0.
- Required full suite: `cd publication-exporter && mvn -q test` exited 0. Fresh Surefire XML totals: **900 tests, 0 failures, 0 errors, 0 skipped**.
- `git diff --quiet HEAD -- publication-exporter/src/test/java/dev/eugene/publicationexporter/LateBoundTargetActivationAcceptanceTest.java` exited 0: Task 9's committed acceptance test is unchanged.
- `git diff --check` exited 0.

## Files changed

- `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMap.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMapCodec.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapCodecTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java`
- `.superpowers/sdd/tasks/task-9a-report.md`

## Self-review findings

- The change is additive: exactly one new public `of(...)` overload was added, and both prior overloads remain source-compatible.
- The safe legacy default is explicit and tested both with and without a legacy `sourceId` key.
- `sourceBodyHash` is excluded from `sameContentAs()` and included in complete value semantics as required.
- Raw and resolved hashes stay distinct: `sourceBodyHash` hashes the admitted raw body, while `ruHash` continues to hash the resolved candidate body.
- The Task 9 acceptance test has no working-tree diff.
- Existing unrelated untracked `.haft` and `openspec/changes/s20-late-bound-target-activation/` artifacts were not modified.

## Concerns

- `graphify update .` was attempted after the code change but exited 1 with exact output: `[graphify watch] Rebuild failed: [Errno 1] Operation not permitted`. It produced no tracked graph changes and does not affect the fresh Maven evidence above.
- No implementation or test concerns remain.
