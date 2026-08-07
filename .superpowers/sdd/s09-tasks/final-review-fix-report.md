# S09 Final Whole-Branch Review Fix Report

**Status:** DONE

Implementation commit: `5a29ba9 fix(s09): resolve final review correctness findings C1-C3 I1-I3`

## Scope and exclusions

This single fix wave resolves C1-C3 and I1-I3 from `final-review-report.md`. Per controller instruction, it does not edit `openspec/changes/s09-replace-approved-snapshot-safely/tasks.md`, does not mark I4 checkboxes, and does not add the M1 stale-second/all-seven replacement test expansion.

## C1 - coherent approved snapshot reads

- Changed `FilesystemApprovedSnapshotWorkspace.read(...)` to capture the approved directory `BasicFileAttributes.fileKey()` before any member read, read all seven files, recapture the key, and return only when both keys match.
- A generation change, disappearance during replacement, or transient member-read failure caused by a generation change retries the whole read. Five unsuccessful attempts throw `ApprovedSnapshotWorkspaceStabilizationException` with the directory and attempt count.
- A replacement lock observed while the canonical directory is temporarily absent prevents a false `Optional.empty()` result; the read retries and eventually returns a coherent generation or the typed stabilization failure.
- Deterministic test `readRetriesWhenApprovedDirectoryGenerationChangesMidRead` pauses immediately after old `ru.md`, completes a real atomic replacement through a second adapter, resumes, and asserts every returned field and reference map is from the new generation.
- Boundary test `readFailsTypedAfterFiveDirectoryGenerationChanges` forces a new generation after `ru.md` on all five attempts and asserts the typed failure.
- Initial red evidence: full Maven run failed with `expected: <New RU> but was: <Old RU>`. Final focused output below is green.

## C2 - cross-process per-identity approval serialization

- Added adapter-owned `withApprovalLock(...)`. The filesystem implementation creates `<review>/<collection>/<id>/.mark-reviewed.lock` with `Files.createFile(...)`; `FileAlreadyExistsException` becomes `ApprovedSnapshotApprovalInProgressException`; release runs in `finally` and preserves the original operation failure if cleanup also fails.
- `install(...)` always enters this filesystem critical section, including direct adapter callers. Same-instance/thread re-entry lets `MarkReviewedHandler` hold the lock across fresh admission, candidate validation, recovery, and its nested install.
- The existing JVM `APPROVAL_LOCKS` remains the fast path. A cross-process collision becomes a schema-v2 `stale` response with an `approved-snapshot` diagnostic.
- `separateInstancesCannotEnterTheSameApprovalCriticalSection` proves independent adapters sharing only the filesystem cannot both enter.
- `secondInstanceCannotInstallWhileFirstInstanceIsMidReplace` pauses the first instance after old canonical becomes a UUID backup, proves the second install is rejected, observes exactly one valid backup, resumes, and reads the fully installed first generation.
- `filesystemApprovalCollisionIsReportedAsStale` proves handler translation.

## C3 - approved snapshot integrity and recovery

- Every approved read validates `references.json` identity plus SHA-256 for RU/EN body, title, and description against the six on-disk values. Missing, malformed, unreadable, identity-mismatched, or hash-mismatched complete directories throw `ApprovedSnapshotIntegrityException`; corrupted bytes are never returned.
- Recovery classifies canonical and backup through the same stable, hash-validating read. Valid canonical plus backup is kept/cleaned and reported. Invalid or absent canonical plus valid backup is restored and reported. Neither valid fails loudly without selecting corrupt data.
- Chosen recovery behavior: strictly delete the invalid canonical, atomically move the already-validated backup to canonical, then throw `ApprovedSnapshotRecoveryException` so the recovery is observable and the caller retries. This is the simpler permitted restore-and-delete option: final canonical bytes are the valid old snapshot, the tampered bytes are gone, and no invalid snapshot is returned.
- `corruptedCanonicalSnapshotIsReplacedByValidBackupAndReported` reproduces the review probe state, asserts the typed recovery event, then asserts old RU/EN/reference map and filesystem bytes.
- Existing incomplete-backup, restored-old, and kept-new tests now validate actual hashes and typed outcomes.
- Initial red evidence: `Expected ApprovedSnapshotRecoveryException to be thrown, but nothing was thrown.` Final focused output below is green.

## I1 - source evidence refreshed inside exclusion

- The first admission identifies the lock key only. After both the JVM and filesystem locks are acquired, `MarkReviewedHandler` re-admits the source and uses only that fresh body/title/description. A changed publication identity is stale.
- `waitingRequestReReadsSourceAfterAcquiringApprovalLock` uses latches only: request 1 holds the identity lock, request 2 admits old source and waits, source changes, request 1 releases, and request 2 returns `stale` with the source-changed diagnostic.
- Initial red evidence: the waiter returned `ok=true` (`expected: <false> but was: <true>`). Final focused output below is green.

## I2 - observable typed failures at every handler boundary

- Added shared typed base `ApprovedSnapshotWorkspaceStateException` for stabilization, integrity, recovery, and approval-in-progress states.
- Translated it alongside existing I/O/confinement handling in `MarkReviewedHandler`, `InspectPublicationHandler`, `PrepareHandler`, `BuildFromReviewHandler`, and the additional grep-discovered consumer `InstallToSiteHandler`.
- CLI acceptance tests `corruptedApprovedSnapshotProducesBlockedSchemaV2Response` cover mark-reviewed, inspect-publication, and prepare. Equivalent command-contract tests `corruptedApprovedSnapshotProducesBlockedJsonInsteadOfEscaping` cover build-from-review and install-to-site. All assert nonzero exit, one parseable blocked JSON result, integrity evidence, and no downstream write.
- Adapter recovery tests prove restored-old and kept-new outcomes are reported rather than silently consumed.

## I3 - exact backup marker recognition

- Backup discovery now uses `NOFOLLOW_LINKS`, requires the complete suffix after `approved-backup-` to parse as a canonical UUID, ignores nonmatching decoys, and throws an integrity exception if more than one valid marker exists.
- `manualBackupDecoyIsIgnoredAndPreserved` keeps `approved-backup-manual/operator-note.txt` byte-for-byte.
- `multipleUuidBackupsFailWithoutDeletingEither` asserts explicit failure and preservation of both directories.

## Combined concurrency probe

The focused run exercises C1 and C2 together through deterministic latch/read/move seams, including an actual reader/replacer overlap and a two-instance mid-replace collision. A probabilistic sleep-based stress loop was not added because the deterministic interleavings reproduce the formerly failing windows exactly and avoid a flaky gate.

Command: `cd publication-exporter && mvn -B -Dtest=FilesystemApprovedSnapshotWorkspaceTest,MarkReviewedHandlerTest test`

```text
[INFO] Scanning for projects...
[INFO]
[INFO] ------------------< dev.eugene:publication-exporter >-------------------
[INFO] Building publication-exporter 0.1.0-SNAPSHOT
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO]
[INFO] --- resources:3.4.0:resources (default-resources) @ publication-exporter ---
[INFO] skip non existing resourceDirectory /Users/eugene/Dev/personal-site/publication-exporter/src/main/resources
[INFO]
[INFO] --- compiler:3.14.0:compile (default-compile) @ publication-exporter ---
[INFO] Nothing to compile - all classes are up to date.
[INFO]
[INFO] --- resources:3.4.0:testResources (default-testResources) @ publication-exporter ---
[INFO] skip non existing resourceDirectory /Users/eugene/Dev/personal-site/publication-exporter/src/test/resources
[INFO]
[INFO] --- compiler:3.14.0:testCompile (default-testCompile) @ publication-exporter ---
[INFO] Nothing to compile - all classes are up to date.
[INFO]
[INFO] --- surefire:3.5.3:test (default-test) @ publication-exporter ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO]
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running dev.eugene.publicationexporter.approved.FilesystemApprovedSnapshotWorkspaceTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.295 s -- in dev.eugene.publicationexporter.approved.FilesystemApprovedSnapshotWorkspaceTest
[INFO] Running dev.eugene.publicationexporter.markreviewed.MarkReviewedHandlerTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.028 s -- in dev.eugene.publicationexporter.markreviewed.MarkReviewedHandlerTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  1.077 s
[INFO] Finished at: 2026-08-07T14:32:09+04:00
[INFO] ------------------------------------------------------------------------
```

## Required full verification

Command: `cd publication-exporter && mvn -B test`

```text
[INFO] Scanning for projects...
[INFO]
[INFO] ------------------< dev.eugene:publication-exporter >-------------------
[INFO] Building publication-exporter 0.1.0-SNAPSHOT
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO]
[INFO] --- resources:3.4.0:resources (default-resources) @ publication-exporter ---
[INFO] skip non existing resourceDirectory /Users/eugene/Dev/personal-site/publication-exporter/src/main/resources
[INFO]
[INFO] --- compiler:3.14.0:compile (default-compile) @ publication-exporter ---
[INFO] Nothing to compile - all classes are up to date.
[INFO]
[INFO] --- resources:3.4.0:testResources (default-testResources) @ publication-exporter ---
[INFO] skip non existing resourceDirectory /Users/eugene/Dev/personal-site/publication-exporter/src/test/resources
[INFO]
[INFO] --- compiler:3.14.0:testCompile (default-testCompile) @ publication-exporter ---
[INFO] Nothing to compile - all classes are up to date.
[INFO]
[INFO] --- surefire:3.5.3:test (default-test) @ publication-exporter ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO]
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running dev.eugene.publicationexporter.candidate.NullCandidateWorkspaceTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.028 s -- in dev.eugene.publicationexporter.candidate.NullCandidateWorkspaceTest
[INFO] Running dev.eugene.publicationexporter.candidate.CandidatePathsTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in dev.eugene.publicationexporter.candidate.CandidatePathsTest
[INFO] Running dev.eugene.publicationexporter.candidate.CandidateSnapshotTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in dev.eugene.publicationexporter.candidate.CandidateSnapshotTest
[INFO] Running dev.eugene.publicationexporter.candidate.FilesystemCandidateWorkspaceTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.192 s -- in dev.eugene.publicationexporter.candidate.FilesystemCandidateWorkspaceTest
[INFO] Running dev.eugene.publicationexporter.note.FrontmatterTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.009 s -- in dev.eugene.publicationexporter.note.FrontmatterTest
[INFO] Running dev.eugene.publicationexporter.inspect.InspectPublicationHandlerTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.035 s -- in dev.eugene.publicationexporter.inspect.InspectPublicationHandlerTest
[INFO] Running dev.eugene.publicationexporter.translation.ProcessTranslationWorkerTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.338 s -- in dev.eugene.publicationexporter.translation.ProcessTranslationWorkerTest
[INFO] Running dev.eugene.publicationexporter.translation.TranslationResultTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in dev.eugene.publicationexporter.translation.TranslationResultTest
[INFO] Running dev.eugene.publicationexporter.translation.CodexTranslationCommandTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in dev.eugene.publicationexporter.translation.CodexTranslationCommandTest
[INFO] Running dev.eugene.publicationexporter.translation.ProcessTranslationWorkerJobConfinementTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.050 s -- in dev.eugene.publicationexporter.translation.ProcessTranslationWorkerJobConfinementTest
[INFO] Running dev.eugene.publicationexporter.translation.TranslationJobTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in dev.eugene.publicationexporter.translation.TranslationJobTest
[INFO] Running dev.eugene.publicationexporter.translation.NullTranslationWorkerTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.004 s -- in dev.eugene.publicationexporter.translation.NullTranslationWorkerTest
[INFO] Running dev.eugene.publicationexporter.prepare.RussianDiffTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.004 s -- in dev.eugene.publicationexporter.prepare.RussianDiffTest
[INFO] Running dev.eugene.publicationexporter.prepare.EnglishCandidateValidatorTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in dev.eugene.publicationexporter.prepare.EnglishCandidateValidatorTest
[INFO] Running dev.eugene.publicationexporter.prepare.PrepareHandlerTest
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.025 s -- in dev.eugene.publicationexporter.prepare.PrepareHandlerTest
[INFO] Running dev.eugene.publicationexporter.hash.ContentHashTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0 s -- in dev.eugene.publicationexporter.hash.ContentHashTest
[INFO] Running dev.eugene.publicationexporter.bridge.ReviewTargetTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in dev.eugene.publicationexporter.bridge.ReviewTargetTest
[INFO] Running dev.eugene.publicationexporter.bridge.SchemaConformanceTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.093 s -- in dev.eugene.publicationexporter.bridge.SchemaConformanceTest
[INFO] Running dev.eugene.publicationexporter.bridge.ReviewPlanTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in dev.eugene.publicationexporter.bridge.ReviewPlanTest
[INFO] Running dev.eugene.publicationexporter.bridge.PublicationIdentityTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in dev.eugene.publicationexporter.bridge.PublicationIdentityTest
[INFO] Running dev.eugene.publicationexporter.bridge.BridgeResponseJsonTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.014 s -- in dev.eugene.publicationexporter.bridge.BridgeResponseJsonTest
[INFO] Running dev.eugene.publicationexporter.admission.EssayAdmissionTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in dev.eugene.publicationexporter.admission.EssayAdmissionTest
[INFO] Running dev.eugene.publicationexporter.installtosite.InstallToSiteHandlerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.012 s -- in dev.eugene.publicationexporter.installtosite.InstallToSiteHandlerTest
[INFO] Running dev.eugene.publicationexporter.release.FilesystemReleaseOutputStoreTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.070 s -- in dev.eugene.publicationexporter.release.FilesystemReleaseOutputStoreTest
[INFO] Running dev.eugene.publicationexporter.release.ReleaseProvenanceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in dev.eugene.publicationexporter.release.ReleaseProvenanceTest
[INFO] Running dev.eugene.publicationexporter.release.NullReleaseOutputStoreTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in dev.eugene.publicationexporter.release.NullReleaseOutputStoreTest
[INFO] Running dev.eugene.publicationexporter.cli.InspectPublicationCliAcceptanceTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.201 s -- in dev.eugene.publicationexporter.cli.InspectPublicationCliAcceptanceTest
[INFO] Running dev.eugene.publicationexporter.cli.MarkReviewedCliAcceptanceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.098 s -- in dev.eugene.publicationexporter.cli.MarkReviewedCliAcceptanceTest
[INFO] Running dev.eugene.publicationexporter.cli.BuildFromReviewCliAcceptanceTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.019 s -- in dev.eugene.publicationexporter.cli.BuildFromReviewCliAcceptanceTest
[INFO] Running dev.eugene.publicationexporter.cli.PrepareCliAcceptanceTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.108 s -- in dev.eugene.publicationexporter.cli.PrepareCliAcceptanceTest
[INFO] Running dev.eugene.publicationexporter.cli.InstallToSiteCliAcceptanceTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.030 s -- in dev.eugene.publicationexporter.cli.InstallToSiteCliAcceptanceTest
[INFO] Running dev.eugene.publicationexporter.intake.NoteIntakeTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in dev.eugene.publicationexporter.intake.NoteIntakeTest
[INFO] Running dev.eugene.publicationexporter.approved.FilesystemApprovedSnapshotWorkspaceTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.136 s -- in dev.eugene.publicationexporter.approved.FilesystemApprovedSnapshotWorkspaceTest
[INFO] Running dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspaceTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in dev.eugene.publicationexporter.approved.NullApprovedSnapshotWorkspaceTest
[INFO] Running dev.eugene.publicationexporter.site.FilesystemManagedSiteInstallerTest
WARNING: site installation failed and locale-file rollback was incomplete; the site is in a torn/orphaned-content state. The site-wide lock remains at /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-5226088831555474913/.astro-export/install-locks/.site.installing. An operator must inspect the site and manually clean up orphaned content before removing this lock. Cause: java.nio.file.FileSystemException: /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-5226088831555474913/site-install-5328072377147824827/release-provenance.json -> /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-5226088831555474913/.astro-export/release-provenance.json: Is a directory
WARNING: site installation committed, but the site-wide lock could not be removed at /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-11321090730839587180/.astro-export/install-locks/.site.installing. Remove this stale lock before the next install. Cause: java.nio.file.AccessDeniedException: /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-11321090730839587180/.astro-export/install-locks/.site.installing
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.443 s -- in dev.eugene.publicationexporter.site.FilesystemManagedSiteInstallerTest
[INFO] Running dev.eugene.publicationexporter.site.SiteReleaseManifestTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.017 s -- in dev.eugene.publicationexporter.site.SiteReleaseManifestTest
[INFO] Running dev.eugene.publicationexporter.site.NullManagedSiteInstallerTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in dev.eugene.publicationexporter.site.NullManagedSiteInstallerTest
[INFO] Running dev.eugene.publicationexporter.site.CheckContentGateContractTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.150 s -- in dev.eugene.publicationexporter.site.CheckContentGateContractTest
[INFO] Running dev.eugene.publicationexporter.markreviewed.MarkReviewedHandlerTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.014 s -- in dev.eugene.publicationexporter.markreviewed.MarkReviewedHandlerTest
[INFO] Running dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandlerTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandlerTest
[INFO] Running dev.eugene.publicationexporter.buildfromreview.ReleaseResultTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in dev.eugene.publicationexporter.buildfromreview.ReleaseResultTest
[INFO] Running dev.eugene.publicationexporter.reference.ReferenceMapTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in dev.eugene.publicationexporter.reference.ReferenceMapTest
[INFO] Running dev.eugene.publicationexporter.reference.ReferenceMapCodecTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in dev.eugene.publicationexporter.reference.ReferenceMapCodecTest
[INFO] Running dev.eugene.publicationexporter.vault.NullVaultReaderTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in dev.eugene.publicationexporter.vault.NullVaultReaderTest
[INFO] Running dev.eugene.publicationexporter.vault.FilesystemVaultReaderTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.013 s -- in dev.eugene.publicationexporter.vault.FilesystemVaultReaderTest
[INFO] Running dev.eugene.publicationexporter.vault.VaultRelativePathTest
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in dev.eugene.publicationexporter.vault.VaultRelativePathTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 399, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  3.987 s
[INFO] Finished at: 2026-08-07T14:32:17+04:00
[INFO] ------------------------------------------------------------------------
```

## Residual concerns

None within C1-C3/I1-I3. The lock-file collision behavior intentionally follows the existing managed-site installer idiom: an unclean process death can leave a stale lock that blocks subsequent approval until an operator removes it; it cannot permit concurrent replacement. `tasks.md`/I4 and M1 remain for the controller as explicitly assigned.
