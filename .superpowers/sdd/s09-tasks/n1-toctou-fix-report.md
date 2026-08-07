# N1 Reclaim-Race TOCTOU Fix Report

## Fix

- A stale-lock observation now captures the PID and `BasicFileAttributes.fileKey()` using `NOFOLLOW_LINKS`.
- Reclaim re-reads the path's attributes immediately before deletion and deletes only when the file key still matches. A missing or replaced lock is left untouched and acquisition retries.
- A successful `CREATE_NEW` write is accepted only after the lock content is read back and matches the current process PID; otherwise acquisition retries.
- A deterministic two-reclaimer test pauses B after it observes the original stale lock, lets A replace and hold it, then proves B neither deletes A's different file nor enters the critical section.

## TDD evidence

RED command:

`cd publication-exporter && mvn -q -Dtest=FilesystemApprovedSnapshotWorkspaceTest#staleLockReclaimerDoesNotDeleteReplacementAcquiredByAnotherReclaimer test`

Actual output (exit 1):

```text
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.043 s <<< FAILURE! -- in dev.eugene.publicationexporter.approved.FilesystemApprovedSnapshotWorkspaceTest
[ERROR] dev.eugene.publicationexporter.approved.FilesystemApprovedSnapshotWorkspaceTest.staleLockReclaimerDoesNotDeleteReplacementAcquiredByAnotherReclaimer -- Time elapsed: 0.036 s <<< ERROR!
java.util.concurrent.ExecutionException: org.opentest4j.AssertionFailedError: Expected dev.eugene.publicationexporter.approved.ApprovedSnapshotApprovalInProgressException to be thrown, but nothing was thrown.
```

GREEN command:

`cd publication-exporter && mvn -q -Dtest=FilesystemApprovedSnapshotWorkspaceTest#staleLockReclaimerDoesNotDeleteReplacementAcquiredByAnotherReclaimer test`

Actual output (exit 0):

```text
<no stdout or stderr>
```

## Required verification

Command:

`cd publication-exporter && mvn -q -Dtest=FilesystemApprovedSnapshotWorkspaceTest,MarkReviewedHandlerTest,MarkReviewedCliAcceptanceTest test`

Actual output (exit 0; 47 tests, 0 failures/errors/skips):

```text
<no stdout or stderr>
```

Command:

`cd publication-exporter && mvn -q test`

Actual output (exit 0; 402 tests, 0 failures/errors/skips):

```text
WARNING: site installation failed and locale-file rollback was incomplete; the site is in a torn/orphaned-content state. The site-wide lock remains at /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-2861333757028817074/.astro-export/install-locks/.site.installing. An operator must inspect the site and manually clean up orphaned content before removing this lock. Cause: java.nio.file.FileSystemException: /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-2861333757028817074/site-install-8647668731492427160/release-provenance.json -> /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-2861333757028817074/.astro-export/release-provenance.json: Is a directory
WARNING: site installation committed, but the site-wide lock could not be removed at /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-9667298177773384341/.astro-export/install-locks/.site.installing. Remove this stale lock before the next install. Cause: java.nio.file.AccessDeniedException: /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-9667298177773384341/.astro-export/install-locks/.site.installing
```

The warnings are emitted by exercised site-installer failure-path tests; Maven exited successfully.
