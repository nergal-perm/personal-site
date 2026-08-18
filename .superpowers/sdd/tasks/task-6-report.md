# Task 6 Report: ApprovedTargetRegistry and OccurrenceMarkerResolver

## Implementation

Implemented the three requested release-stage types in `publication-exporter`:

- `ApprovedTargetRegistry` snapshots distinct occurrence target source IDs once per factory call, looks up approved snapshots with `findBySourceId`, and builds locale-prefixed, kind-correct routes through `PublicationKinds.installed().forIdentity(...).routePrefix()`.
- `OccurrenceMarkerResolver` is a stateless regex-based resolver for the exact marker shape emitted by `LinkResolver`: `[label](ref:sourceId)`. Approved targets become locale routes; missing targets become plain labels; activation and deactivation counts are returned.
- `OccurrenceResolution` is the immutable result record with null-rejected body and count accessors.

The route implementation uses `CandidateSnapshot.referenceMap().identity()` for collection, content type, and public ID. The marker regex was checked against `LinkResolver.appendAdmittedNonEmbed`, which emits `](ref:` followed by the source ID and `)`.

## TDD evidence

RED command:

```text
cd publication-exporter && mvn -q -Dtest=ApprovedTargetRegistryTest test
```

Result: exit 1. Maven test compilation failed with `cannot find symbol` for `ApprovedTargetRegistry`, `OccurrenceMarkerResolver`, and `OccurrenceResolution`, as expected before production code existed.

GREEN focused command:

```text
cd publication-exporter && mvn -q -Dtest=ApprovedTargetRegistryTest,OccurrenceMarkerResolverTest test
```

Result: exit 0.

Full-suite command:

```text
cd publication-exporter && mvn -q test
```

Result: exit 0. The only output was the existing JUnit warning about deleting temporary-directory symlinks whose targets are outside the temporary directory.

## Files changed

- `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/ApprovedTargetRegistry.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/OccurrenceMarkerResolver.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/release/OccurrenceResolution.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/release/ApprovedTargetRegistryTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/release/OccurrenceMarkerResolverTest.java`

No files under `exporter-java/` were modified.

## Self-review

- Production collaborators contain no comments; the implementation is small and stateless where required.
- Null inputs are rejected by the existing value-object/`Objects.requireNonNull` style, and lookups expose `Optional` rather than a null sentinel.
- Duplicate target source IDs are resolved only once while building the registry.
- Unknown publication kind data in an approved snapshot fails explicitly with an `IllegalStateException`; approved snapshots are expected to carry identities admitted by the installed kind registry.
- Existing dirty files were preserved: `.haft/method-runs/...`, `.haft/problems/...`, and `openspec/changes/s20-late-bound-target-activation/` were already untracked before this task.
- `git diff --check` passed for the tracked diff state; the new files were also reviewed directly.

## Concerns

The required `graphify update .` was attempted after the code change. It failed in the environment with the exact error:

```text
[graphify watch] Rebuild failed: [Errno 1] Operation not permitted
```

This prevented graph refresh only. The focused and full Maven tests passed independently. The full suite also emitted the JUnit symlink warnings described above.

## Commit

Commit succeeded:

```text
16cbdf1 feat: add ApprovedTargetRegistry and OccurrenceMarkerResolver
```

## Fixes after review

- Made `ApprovedTargetRegistry.forOccurrences` package-private as specified.
- Replaced the resolver's occurrence lookup/null branch with `Optional` and confirmed there are no bare-null checks in either release class.
- Decomposed `OccurrenceMarkerResolver.resolve` into occurrence indexing, marker substitution, and route-selection helpers.
- Strengthened coverage with distinct inline/stored fallback labels, mixed approved/unapproved markers and count assertions, an empty-occurrences marker case, zero-entry registry coverage, and explicit non-Russian/English behavior.
- Test output included the existing unrelated JUnit temporary-directory symlink warning; it was not fully clean.
```
