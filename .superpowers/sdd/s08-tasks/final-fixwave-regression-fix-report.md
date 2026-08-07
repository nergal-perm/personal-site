# Scoped re-review regression fix

## Change

Changed `EnglishCandidateValidator` to remove spans matched by its existing `EXTERNAL_URL` pattern before scanning body, title, and description text for the internal `/ru/` marker. This preserves detection outside external URLs, including the existing inline Markdown, reference-style, HTML, title, and description cases.

## Regression test

Added `acceptsRetainedExternalUrlContainingRuRouteInAllFields`, which retains `https://example.com/ru/docs` verbatim from the RU body into the EN body and also includes it in the EN title and description. The candidate is accepted as valid.

The test was first run against the regressed implementation and failed:

```text
[ERROR] Tests run: 12, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.036 s <<< FAILURE! -- in dev.eugene.publicationexporter.prepare.EnglishCandidateValidatorTest
[ERROR] dev.eugene.publicationexporter.prepare.EnglishCandidateValidatorTest.acceptsRetainedExternalUrlContainingRuRouteInAllFields -- Time elapsed: 0.003 s <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <true> but was: <false>
```

## Final test output

Command:

```text
cd publication-exporter && mvn -q -Dtest=EnglishCandidateValidatorTest test
```

Actual output: no stdout/stderr; exit code 0.

Command:

```text
cd publication-exporter && mvn -q test
```

Actual output (exit code 0):

```text
WARNING: site installation failed and locale-file rollback was incomplete; the site is in a torn/orphaned-content state. The site-wide lock remains at /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-6959429109179305651/.astro-export/install-locks/.site.installing. An operator must inspect the site and manually clean up orphaned content before removing this lock. Cause: java.nio.file.FileSystemException: /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-6959429109179305651/site-install-14912902163963638863/release-provenance.json -> /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-6959429109179305651/.astro-export/release-provenance.json: Is a directory
WARNING: site installation committed, but the site-wide lock could not be removed at /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-6877998842903452396/.astro-export/install-locks/.site.installing. Remove this stale lock before the next install. Cause: java.nio.file.AccessDeniedException: /private/var/folders/0h/5zqxlwls44d7btsv9j06ssz00000gn/T/junit-6877998842903452396/.astro-export/install-locks/.site.installing
```
