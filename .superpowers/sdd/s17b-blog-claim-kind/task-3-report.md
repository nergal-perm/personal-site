# S17b Task 3 report

## Status

DONE_WITH_CONCERNS

Task 3's model and codec generalization is implemented and the changed unit
surface passes isolated compilation and execution. The normal Maven module
build remains blocked by the intentionally deferred old-API callers.

Commit: `e789763 refactor(publication-exporter): generalize candidate snapshots and reference maps`

## Implemented

- Generalized `CandidateSnapshot` to carry `ruBody`, `enBody`, immutable
  `ruFields`, immutable `enFields`, `structuredData`, and `ReferenceMap`.
- Added the requested six-argument `CandidateSnapshot.of(...)` factory and
  replaced the old title/description accessors with `ruFields()`, `enFields()`,
  and `structuredData()`.
- Applied `List.copyOf(...)` to both stored field lists and updated
  `equals`, `hashCode`, and `toString` for the new value shape.
- Added `PublicField.value(List<PublicField>, String)` returning
  `Optional<String>` as the shared field-key lookup helper.
- Generalized `ReferenceMap.empty(...)` to `(identity, ruHash, enHash,
  ruFieldsHash, enFieldsHash, structuredDataHash)`.
- Replaced the four named title/description hash values with
  `ruFieldsHash`, `enFieldsHash`, and `structuredDataHash` in the accessors,
  value semantics, content comparison, and string representation.
- Updated `ReferenceMapCodec.referenceMapFrom(...)` to read the three new
  JSON property names; Jackson serialization continues to be driven by the
  existing `@JsonProperty` accessors.
- Updated `CandidateSnapshotTest`, `ReferenceMapTest`, and
  `ReferenceMapCodecTest` to the new field/hash shape and preserved their
  construction, value-semantics, identity-insensitive content comparison,
  null rejection, JSON, and round-trip assertions.
- Added lookup coverage to `PublicFieldTest`.
- Left the explicitly deferred `PrepareHandler.java`, `PrepareHandlerTest.java`,
  `FilesystemCandidateWorkspace.java`, and
  `FilesystemApprovedSnapshotWorkspace.java` untouched.

## Verification

Focused isolated verification compiled these production classes:
`PublicationIdentity`, `PublicField`, `ReferenceMap`, `ReferenceMapCodec`, and
`CandidateSnapshot`. It then compiled and ran exactly:
`CandidateSnapshotTest`, `ReferenceMapTest`, `ReferenceMapCodecTest`, and
`PublicFieldTest` through the JUnit Platform launcher.

Result:

```text
19 tests found
19 tests started
19 tests successful
0 tests failed
```

`git diff --check` passed.

`mvn -q test-compile 2>&1` exited 1 during main-source compilation. The
Maven error paths were the nine production files listed below; Maven did not
reach test compilation. The brief's `mvn -q test` expectation was also
attempted: it exited 1 with the same nine main-source error paths and no test
execution.

An all-source isolated `javac --release 17 -proc:none -Xmaxerrs 1000` audit
compiled every main and test Java source and exited 1 with 257 diagnostics and
27 unique error-file paths. This is the complete remaining compile-error set
observed after Task 3:

```text
publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspace.java
publication-exporter/src/main/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspace.java
publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateWorkspace.java
publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspace.java
publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspace.java
publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java
publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java
publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java
publication-exporter/src/main/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstaller.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/CrossKindAddressCollisionAcceptanceTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/FilesystemApprovedSnapshotWorkspaceTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/approved/NullApprovedSnapshotWorkspaceTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/buildfromreview/BuildFromReviewHandlerTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/FilesystemCandidateWorkspaceTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/NullCandidateWorkspaceTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/BuildFromReviewCliAcceptanceTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/InstallToSiteCliAcceptanceTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/PrepareCliAcceptanceTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandlerTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandlerTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandlerTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/refresh/RefreshPublicationQueueHandlerTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/site/AstroBuildSmokeIT.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/site/CheckContentGateContractTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstallerTest.java
publication-exporter/src/test/java/dev/eugene/publicationexporter/site/NullManagedSiteInstallerTest.java
```

The Maven-only list is shorter because compilation stops before tests. The
additional files in the all-source list are old `CandidateSnapshot` or
`ReferenceMap` callers that the next migration tasks will need to update.

`graphify update .` was attempted after the source edits and failed in the
environment with `Operation not permitted` while rebuilding the graph.

## Files changed

- `publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateSnapshot.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/PublicField.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMap.java`
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMapCodec.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/candidate/CandidateSnapshotTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/PublicFieldTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapCodecTest.java`
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/reference/ReferenceMapTest.java`
- `.superpowers/sdd/s17b-blog-claim-kind/task-3-report.md`

## Self-review

- Changed production value types remain `public final` with private all-args
  constructors, named static factories, null rejection, and complete value
  semantics.
- `CandidateSnapshot` stores both field lists through `List.copyOf(...)`.
- No getter- or setter-prefixed production methods were added; the scan's only
  `get...` matches were test calls to exception message accessors.
- `PublicField.value(...)` is the only field-key lookup loop in the module
  after this change, and the focused test exercises both a match and a miss.
- No production comments were added; no non-obvious rationale required one.
- The title/description positional convention remains represented by the
  existing ordered field construction in callers; this task does not change
  that construction policy.

## Concerns

1. The observed compile-error set is broader than the four example test files
   in the brief. It includes old API users in null adapters and handlers as
   well as their tests. They were not changed because this task explicitly
   scopes the migration to the model/codec layer and defers caller updates.
2. The normal Maven suite cannot run until those later callers are migrated.
3. `graphify update .` remains blocked by the environment's
   `Operation not permitted` error.
